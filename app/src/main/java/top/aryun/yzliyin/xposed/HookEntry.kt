package top.aryun.yzliyin.xposed

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field

/**
 * LSPosed 模块入口，由 `assets/xposed_init` 指名，manifest 里的 `xposedmodule`
 * 元数据负责让 LSPosed 认得出本应用是模块（缺一不可，否则装完既不提示激活、
 * 模块列表里也找不到）。
 *
 * 干的事只有一件：在**系统设置首页**插一个「音之理音」入口，点了就拉起本应用。
 * 开关、位置、图标样式跟设置 → Hook 那一节的四项走，配置用 [XSharedPreferences]
 * 从模块自己的偏好里读，读不到就退回与设置页相同的默认值（默认即「开 / 顶部」）。
 *
 * ## 为什么有两条路径
 *
 * 设置首页在各家 ROM 上根本不是同一套东西：
 *
 * - **MIUI / HyperOS**：首页是 `MiuiSettings` 用一个 `Header` 列表渲染的
 *   （`com.android.settingslib.miuisettings.preference.PreferenceActivity$Header`），
 *   跟 AOSP 的 preference 页面完全是两回事。**挂 AOSP 那条路在这类机器上永远不会触发。**
 * - **AOSP 及多数类原生 ROM**：首页是 `TopLevelSettings` 这个 preference fragment。
 *
 * 所以先试 MIUI 路径，挂上了就**不再**走 AOSP 路径，免得同一个入口被插到两个地方；
 * MIUI 路径失败才退到 AOSP。
 *
 * ## 与 HyperIsland 共存
 *
 * HyperIsland 用的是同一套 MIUI 注入手法。为了两个模块能同时在列表里各占一行：
 *
 * - 入口条目用各自的 [HEADER_ID]，插入前只按**自己的** ID 查重；
 * - 图标用各自的假资源 ID（[ICON_FAKE_RES_ID] 与它的 0x7e00f001 错开），
 *   `Resources.getDrawable` 上两套 hook 各认各的 ID，不匹配时都放行给下一个，
 *   谁也不会把谁的图标顶掉。
 *
 * 每一步都单独 runCatching：钩不上就安静放弃并写一条日志，绝不把系统设置进程带崩。
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PKG) return
        hostLoader = lpparam.classLoader
        if (!installMiui(lpparam.classLoader)) installAosp(lpparam.classLoader)
    }

    /** 被 hook 的那个进程的类加载器：Header / Preference 都要从它那儿取。 */
    @Volatile
    private var hostLoader: ClassLoader? = null

    /** 拿到的第一个可用 Context，用来读模块自己的资源（标签与图标）。 */
    @Volatile
    private var hostContext: Context? = null

    @Volatile
    private var iconHookInstalled = false

    // =========================================================== MIUI / HyperOS

    /**
     * MIUI / HyperOS 路径：往 `MiuiSettings.updateHeaderList` 收集好的 Header 列表里
     * 插一条指向本应用的条目。返回是否真的挂上了。
     */
    private fun installMiui(cl: ClassLoader): Boolean {
        val cls = runCatching { XposedHelpers.findClass("com.android.settings.MiuiSettings", cl) }
            .getOrNull()
        if (cls == null) {
            log("MIUI：没找到 com.android.settings.MiuiSettings，这不是 MIUI/HyperOS 设置")
            return false
        }
        val method = runCatching {
            XposedHelpers.findMethodExact(cls, "updateHeaderList", List::class.java)
        }.getOrNull()
        if (method == null) {
            log("MIUI：没找到 updateHeaderList(List)，这一版设置改结构了")
            return false
        }

        installIconHook()
        val ok = runCatching {
            XposedBridge.hookMethod(
                method,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        @Suppress("UNCHECKED_CAST")
                        val headers = param.args.getOrNull(0) as? MutableList<Any> ?: return
                        hostContext = hostContext ?: activity
                        runCatching { insertMiuiHeader(activity, cl, headers) }
                            .onFailure { log("MIUI：插入入口失败 $it") }
                    }
                },
            )
        }.isSuccess
        log(if (ok) "MIUI：已挂上 MiuiSettings.updateHeaderList" else "MIUI：挂钩失败")
        return ok
    }

    private fun insertMiuiHeader(activity: Activity, cl: ClassLoader, headers: MutableList<Any>) {
        // 只按自己的 ID 查重：HyperIsland 的条目也在这个列表里，不能把它当成「已存在」
        if (headers.any { readLong(it, FIELD_ID) == HEADER_ID }) return

        val cfg = config()
        if (!cfg.entry) return

        val context = activity.baseContext ?: activity
        val headerCls = runCatching { XposedHelpers.findClass(MIUI_HEADER_CLASS, cl) }.getOrNull()
        if (headerCls == null) {
            log("MIUI：没找到 PreferenceActivity\$Header，无法构造条目")
            return
        }
        val label = moduleLabel(context)
        if (label == null) {
            log("MIUI：读不到模块标签（包可见性？），跳过注入")
            return
        }

        val header = runCatching {
            headerCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrNull() ?: return

        writeLong(header, FIELD_ID, HEADER_ID)
        writeObject(header, FIELD_TITLE, label)
        writeInt(header, FIELD_ICON_RES, ICON_FAKE_RES_ID)
        writeObject(header, FIELD_INTENT, launchIntent())

        val position = findMiuiInsertPosition(context, headers)
        //「归入同一栏目」：MIUI 靠 groupId 分组，继承相邻条目就落在同一组里
        if (cfg.sameGroup) inheritGroupId(headers, header, position)
        headers.add(position.coerceIn(0, headers.size), header)
        log("MIUI：已插入系统设置入口（位置=${cfg.position} 同栏目=${cfg.sameGroup}）")
    }

    /** 按位置锚点找插入下标；锚点是 MIUI 自己的资源名，找不到就退回末尾/开头。 */
    private fun findMiuiInsertPosition(context: Context, headers: List<Any>): Int {
        val res = context.resources
        val anchorNames = when (config().position) {
            POSITION_TOP -> listOf("my_device")
            POSITION_MIDDLE -> listOf("launcher_settings")
            else -> listOf("app_timer", "other_special_feature_settings")
        }
        val anchors = anchorNames
            .mapNotNull { name ->
                runCatching { res.getIdentifier(name, "id", TARGET_PKG) }.getOrNull()
            }
            .filter { it != 0 }
            .map { it.toLong() }
            .toSet()
        if (anchors.isEmpty()) return 0
        headers.forEachIndexed { index, header ->
            if (readLong(header, FIELD_ID) in anchors) return index + 1
        }
        return if (headers.size > 25) 25 else headers.size
    }

    private fun inheritGroupId(headers: List<Any>, header: Any, position: Int) {
        if (headers.isEmpty()) return
        val neighbour = headers[(position - 1).coerceIn(0, headers.lastIndex)]
        val groupId = readIntOrNull(neighbour, FIELD_GROUP_ID) ?: return
        writeInt(header, FIELD_GROUP_ID, groupId)
    }

    // ------------------------------------------------------------ 图标（假资源 ID）

    /**
     * [Header][android.preference.PreferenceActivity.Header] 只存一个 int 资源 ID，
     * MIUI 渲染时拿它去 Resources 取图。这个 ID 是假的、真去取必然 NotFound，
     * 所以 hook 住所有 `getDrawable(int, ...)` 重载，认出我们的 ID 就换成模块自己的图标。
     *
     * 用 beforeHookedMethod 直接给 result，让原方法根本不执行；不匹配时什么都不做，
     * 交给链上的下一个 hook（HyperIsland 的也在同一批方法上），谁也不会顶掉谁。
     */
    private fun installIconHook() {
        if (iconHookInstalled) return
        val overloads = runCatching {
            Resources::class.java.declaredMethods.filter {
                it.name == "getDrawable" &&
                    it.parameterTypes.isNotEmpty() &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
        }.getOrDefault(emptyList())

        overloads.forEach { method ->
            runCatching {
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (param.args.getOrNull(0) as? Int != ICON_FAKE_RES_ID) return
                            // 绝不塞 null：设置拿这个图标去画，null 会把它画崩。
                            // 真取不到模块图标就给个程序生成的圆点，难看但不会出事。
                            param.result = moduleIcon(param.thisObject as? Resources)
                                ?: GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(0xFF4CAF50.toInt())
                                }
                        }
                    },
                )
            }
        }
        iconHookInstalled = overloads.isNotEmpty()
        log(
            if (iconHookInstalled) "已挂上 Resources.getDrawable 供图标（${overloads.size} 个重载）"
            else "没有可挂的 getDrawable 重载，入口将没有图标",
        )
    }

    private fun moduleIcon(resources: Resources?): Drawable? = runCatching {
        val base = runCatching {
            val ctx = hostContext?.let { moduleContext(it) } ?: return@runCatching null
            val id = ctx.resources.getIdentifier("ic_launcher", "mipmap", APP_PKG)
            if (id != 0) ctx.resources.getDrawable(id, null) else null
        }.getOrNull()
            ?: resources?.getDrawable(android.R.drawable.ic_media_play, null)
            ?: return@runCatching null
        if (config().iconStyle == ICON_OUTLINE) withOutline(base, resources) else base
    }.getOrNull()

    // ================================================================ AOSP 路径

    /** AOSP 及类原生 ROM：挂设置首页那个 preference fragment 的 onCreatePreferences。 */
    private fun installAosp(cl: ClassLoader): Boolean {
        val cls = runCatching {
            XposedHelpers.findClass("com.android.settings.homepage.TopLevelSettings", cl)
        }.getOrNull()
        if (cls == null) {
            log("AOSP：没找到 TopLevelSettings，两条路径都没挂上，本 ROM 暂不支持入口注入")
            return false
        }
        val method = hookDeclared(cl, cls, "onCreatePreferences", PREFERENCES_ARGS)
        log(if (method) "AOSP：已挂上 TopLevelSettings.onCreatePreferences" else "AOSP：挂钩失败")
        return method
    }

    private val aospHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val fragment = param.thisObject ?: return
            // 只认设置首页那个 fragment，别把入口撒到设置的每个子页面上
            if (!fragment.javaClass.name.contains("TopLevelSettings")) return
            runCatching { injectAosp(fragment) }.onFailure { log("AOSP：注入失败 $it") }
        }
    }

    /** 沿继承链找第一个声明了 [method] 的类并挂钩；Xposed 只拦真正执行的那个方法。 */
    private fun hookDeclared(
        cl: ClassLoader,
        start: Class<*>,
        method: String,
        paramNames: Array<String>,
    ): Boolean {
        var cls: Class<*>? = start
        while (cls != null && cls != Any::class.java) {
            val params = runCatching {
                paramNames.map { n -> XposedHelpers.findClass(n, cl) }.toTypedArray()
            }.getOrNull() ?: return false
            val declared = runCatching { cls.getDeclaredMethod(method, *params) }.getOrNull()
            if (declared != null) return runCatching { XposedBridge.hookMethod(declared, aospHook) }.isSuccess
            cls = cls.superclass
        }
        return false
    }

    private fun injectAosp(fragment: Any) {
        val screen = runCatching { XposedHelpers.callMethod(fragment, "getPreferenceScreen") }
            .getOrNull() ?: return
        val context = contextOf(fragment) ?: return
        hostContext = hostContext ?: context

        val cfg = config()
        if (!cfg.entry) return
        if (runCatching { XposedHelpers.callMethod(screen, "findPreference", ENTRY_KEY) }
                .getOrNull() != null
        ) {
            return
        }

        val prefCls = pickPrefClass() ?: return
        val pref = XposedHelpers.newInstance(prefCls, context)
        runCatching { XposedHelpers.callMethod(pref, "setKey", ENTRY_KEY) }
        runCatching { XposedHelpers.callMethod(pref, "setTitle", moduleLabel(context) ?: APP_PKG) }
        appString(context, "set_hook_settings_entry_summary")?.let {
            runCatching { XposedHelpers.callMethod(pref, "setSummary", it) }
        }
        runCatching { entryIcon(context, cfg.iconStyle) }.getOrNull()?.let {
            runCatching { XposedHelpers.callMethod(pref, "setIcon", it) }
        }
        runCatching { XposedHelpers.callMethod(pref, "setPersistent", false) }
        runCatching { XposedHelpers.callMethod(pref, "setIntent", launchIntent()) }

        val target = if (cfg.sameGroup) pref
        else wrapInCategory(context, pref, moduleLabel(context)?.toString() ?: APP_PKG) ?: pref

        insertAt(fragment, screen, target, cfg.position)
    }

    private fun pickPrefClass(): Class<*>? {
        val cl = hostLoader ?: return null
        return runCatching { XposedHelpers.findClass(PREF_ANDROIDX, cl) }.getOrNull()
            ?: runCatching { XposedHelpers.findClass(PREF_FRAMEWORK, cl) }.getOrNull()
    }

    private fun wrapInCategory(context: Context, pref: Any, title: String): Any? {
        val cl = hostLoader ?: return null
        val cls = runCatching { XposedHelpers.findClass(CATEGORY_ANDROIDX, cl) }.getOrNull()
            ?: runCatching { XposedHelpers.findClass(CATEGORY_FRAMEWORK, cl) }.getOrNull()
            ?: return null
        val cat = XposedHelpers.newInstance(cls, context)
        runCatching { XposedHelpers.callMethod(cat, "setKey", ENTRY_KEY + "_group") }
        runCatching { XposedHelpers.callMethod(cat, "setTitle", title) }
        runCatching { XposedHelpers.callMethod(cat, "setPersistent", false) }
        runCatching { XposedHelpers.callMethod(cat, "addPreference", pref) }
        return cat
    }

    /**
     * 先照常加进去（让 Preference 自己维护父子关系），再把顺序表按位置挪。
     * 不能只靠 `setOrder`：首页是按「加进来的顺序」渲染的，改 order 不保证重排。
     */
    private fun insertAt(fragment: Any, screen: Any, target: Any, position: String) {
        val added = runCatching {
            XposedHelpers.callMethod(screen, "addPreference", target) as Boolean
        }.getOrDefault(false)
        if (!added) return

        val count = runCatching { XposedHelpers.callMethod(screen, "getPreferenceCount") as Int }
            .getOrDefault(0)
        val toIndex = when (position) {
            POSITION_TOP -> 0
            POSITION_BOTTOM -> (count - 1).coerceAtLeast(0)
            else -> count / 2
        }
        shift(groupList(screen), target, toIndex)
        shift(adapterList(fragment), target, toIndex)

        val listView = runCatching { XposedHelpers.callMethod(fragment, "getListView") }.getOrNull()
        val adapter = runCatching { XposedHelpers.callMethod(listView, "getAdapter") }.getOrNull()
        runCatching { XposedHelpers.callMethod(adapter, "notifyDataSetChanged") }
    }

    private fun groupList(screen: Any): MutableList<Any>? = runCatching {
        @Suppress("UNCHECKED_CAST")
        XposedHelpers.getObjectField(screen, "mPreferenceList") as? MutableList<Any>
    }.getOrNull()

    private fun adapterList(fragment: Any): MutableList<Any>? {
        val listView = runCatching { XposedHelpers.callMethod(fragment, "getListView") }.getOrNull()
            ?: return null
        val adapter = runCatching { XposedHelpers.callMethod(listView, "getAdapter") }.getOrNull()
            ?: return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            XposedHelpers.getObjectField(adapter, "mVisibleList") as? MutableList<Any>
        }.getOrNull()
    }

    private fun shift(list: MutableList<Any>?, target: Any, toIndex: Int) {
        if (list == null) return
        val from = list.indexOf(target)
        if (from < 0) {
            list.add(toIndex.coerceIn(0, list.size), target)
            return
        }
        if (from == toIndex) return
        list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), target)
    }

    private fun contextOf(fragment: Any): Context? =
        runCatching { XposedHelpers.callMethod(fragment, "requireContext") as Context }.getOrNull()
            ?: runCatching { XposedHelpers.callMethod(fragment, "getActivity") as? Context }
                .getOrNull()

    private fun entryIcon(context: Context, style: String): Drawable? {
        val base = runCatching { context.packageManager.getApplicationIcon(APP_PKG) }.getOrNull()
            ?: return null
        return if (style == ICON_OUTLINE) withOutline(base, null) else base
    }

    // ============================================================== 公共部分

    private fun launchIntent(): Intent = Intent().apply {
        setClassName(APP_PKG, "$APP_PKG.$MAIN_ACTIVITY")
        // 当作系统设置的子页面打开：不加 NEW_TASK，返回键自然回到设置主页
        putExtra("isDisplayHomeAsUpEnabled", true)
    }

    /** 用 createPackageContext 拿模块自己的资源与标签，与 HyperIsland 同一手法。 */
    private fun moduleContext(from: Context): Context? = runCatching {
        from.createPackageContext(APP_PKG, Context.CONTEXT_IGNORE_SECURITY)
    }.getOrNull()

    private fun moduleLabel(context: Context): CharSequence? = runCatching {
        val pkg = moduleContext(context) ?: return null
        pkg.packageManager.getApplicationLabel(pkg.applicationInfo)
    }.getOrNull()

    /** 描边样式：外面套一圈圆角描边，颜色跟设置页底色走，深浅主题都看得见。 */
    private fun withOutline(icon: Drawable, resources: Resources?): Drawable {
        val density = resources?.displayMetrics?.density
            ?: hostContext?.resources?.displayMetrics?.density
            ?: 1f
        return LayerDrawable(
            arrayOf(
                icon,
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = density * 8f
                    setStroke((density * 1.5f).toInt().coerceAtLeast(2), outlineStroke())
                },
            ),
        )
    }

    private fun outlineStroke(): Int {
        val bg = runCatching {
            val v = TypedValue()
            val theme = hostContext?.theme
            if (theme != null &&
                theme.resolveAttribute(android.R.attr.colorBackground, v, true) &&
                v.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
            ) {
                v.data
            } else {
                0xFFFFFFFF.toInt()
            }
        }.getOrDefault(0xFFFFFFFF.toInt())
        val luma = 0.299 * ((bg shr 16) and 0xFF) +
            0.587 * ((bg shr 8) and 0xFF) +
            0.114 * (bg and 0xFF)
        return if (luma < 128) 0x80FFFFFF.toInt() else 0x80000000.toInt()
    }

    /** 从模块自己的资源里取文案，设置进程里没有本应用的字符串资源。 */
    private fun appString(context: Context, name: String): String? = runCatching {
        val res = context.packageManager.getResourcesForApplication(APP_PKG)
        val id = res.getIdentifier(name, "string", APP_PKG)
        if (id == 0) null else res.getString(id)
    }.getOrNull()

    private fun fieldInHierarchy(cls: Class<*>, name: String): Field? {
        var c: Class<*>? = cls
        while (c != null) {
            runCatching {
                c.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()?.let { return it }
            c = c.superclass
        }
        return null
    }

    private fun readLong(target: Any, name: String): Long =
        runCatching { fieldInHierarchy(target.javaClass, name)?.getLong(target) }.getOrNull()
            ?: Long.MIN_VALUE

    private fun readIntOrNull(target: Any, name: String): Int? =
        runCatching { fieldInHierarchy(target.javaClass, name)?.getInt(target) }.getOrNull()

    private fun writeLong(target: Any, name: String, value: Long) {
        runCatching { fieldInHierarchy(target.javaClass, name)?.setLong(target, value) }
    }

    private fun writeInt(target: Any, name: String, value: Int) {
        runCatching { fieldInHierarchy(target.javaClass, name)?.setInt(target, value) }
    }

    private fun writeObject(target: Any, name: String, value: Any?) {
        runCatching { fieldInHierarchy(target.javaClass, name)?.set(target, value) }
    }

    private fun log(message: String) = runCatching { XposedBridge.log("$TAG $message") }

    // ------------------------------------------------------------------ 配置

    private data class Config(
        val entry: Boolean,
        val sameGroup: Boolean,
        val position: String,
        val iconStyle: String,
    )

    @Volatile
    private var cachedConfig: Config? = null

    @Volatile
    private var cachedAtMs = 0L

    private fun config(): Config {
        val now = System.currentTimeMillis()
        cachedConfig?.let { if (now - cachedAtMs < CONFIG_TTL_MS) return it }
        val prefs = runCatching {
            XSharedPreferences(APP_PKG, PREF_NAME).apply { makeWorldReadable() }
        }.getOrNull()
        runCatching { prefs?.reload() }
        fun bool(key: String, def: Boolean): Boolean =
            prefs?.let { runCatching { it.getBoolean(key, def) }.getOrDefault(def) } ?: def
        fun str(key: String, def: String): String =
            prefs?.let { runCatching { it.getString(key, def) ?: def }.getOrDefault(def) } ?: def
        val cfg = Config(
            entry = bool(KEY_ENTRY, true),
            sameGroup = bool(KEY_SAME_GROUP, true),
            position = str(KEY_POSITION, POSITION_TOP),
            iconStyle = str(KEY_ICON_STYLE, ICON_DEFAULT),
        )
        cachedConfig = cfg
        cachedAtMs = now
        return cfg
    }

    private companion object {
        const val TAG = "YZLiYin/Hook: "
        const val TARGET_PKG = "com.android.settings"
        const val APP_PKG = "top.aryun.yzliyin"
        const val MAIN_ACTIVITY = "MainActivity"

        /** 与 SessionStore.PREF_NAME / KEY_HOOK_* 一一对应，改那边要同步改这里。 */
        const val PREF_NAME = "yzliyin_session"
        const val KEY_ENTRY = "hook_settings_entry"
        const val KEY_SAME_GROUP = "hook_settings_same_group"
        const val KEY_POSITION = "hook_settings_position"
        const val KEY_ICON_STYLE = "hook_settings_icon_style"
        const val POSITION_TOP = "top"
        const val POSITION_MIDDLE = "middle"
        const val POSITION_BOTTOM = "bottom"
        const val ICON_DEFAULT = "default"
        const val ICON_OUTLINE = "outline"

        const val ENTRY_KEY = "yzliyin_settings_entry"

        // ---- MIUI / HyperOS：Header 列表 ----
        const val MIUI_HEADER_CLASS =
            "com.android.settingslib.miuisettings.preference.PreferenceActivity\$Header"

        /** 本模块的条目 ID。必须与 HyperIsland 的 0x68797065726C 错开，两边才各占一行。 */
        const val HEADER_ID = 0x797A6C6979696EL

        /**
         * 假资源 ID，只在 [Resources.getDrawable] 的 hook 里有意义。
         * 必须落在真实资源 ID 之外的区间（应用是 0x7f……、框架是 0x01……），
         * 且与 HyperIsland 的 0x7e00f001 错开。
         */
        const val ICON_FAKE_RES_ID = 0x7A796C01

        const val FIELD_ID = "id"
        const val FIELD_TITLE = "title"
        const val FIELD_ICON_RES = "iconRes"
        const val FIELD_INTENT = "intent"
        const val FIELD_GROUP_ID = "groupId"

        // ---- AOSP：Preference ----
        const val PREF_ANDROIDX = "androidx.preference.Preference"
        const val PREF_FRAMEWORK = "android.preference.Preference"
        const val CATEGORY_ANDROIDX = "androidx.preference.PreferenceCategory"
        const val CATEGORY_FRAMEWORK = "android.preference.PreferenceCategory"
        val PREFERENCES_ARGS = arrayOf("android.os.Bundle", "java.lang.String")

        const val CONFIG_TTL_MS = 5_000L
    }
}
