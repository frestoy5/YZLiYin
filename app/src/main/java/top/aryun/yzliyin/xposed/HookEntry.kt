package top.aryun.yzliyin.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

/**
 * LSPosed 模块入口，由 `assets/xposed_init` 指名，manifest 里的 `xposedmodule`
 * 元数据负责让 LSPosed 认得出本应用是模块（缺一不可，否则装完既不提示激活、
 * 模块列表里也找不到）。
 *
 * 干的事只有一件：在**系统设置**里往设置首页插一个「音之理音」入口，点了就拉起本应用。
 * 开关、位置、图标样式跟设置 → Hook 那一节的四项走，配置用 [XSharedPreferences]
 * 从模块自己的偏好里读，读不到就退回与设置页相同的默认值（默认即「开 / 顶部」）。
 *
 * 设置首页在各家 ROM 上长什么样没准（AOSP、MIUI、ColorOS、OneUI 各一套），
 * 所以每一步都单独 runCatching：钩不上就安静放弃并写一条日志，绝不把系统设置进程带崩。
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PKG) return
        hostLoader = lpparam.classLoader
        install(lpparam.classLoader)
    }

    /** 被 hook 的那个进程的类加载器：Preference / PreferenceCategory 都要从它那儿取。 */
    @Volatile
    private var hostLoader: ClassLoader? = null

    // ---------------------------------------------------------------- 注入时机

    private fun install(cl: ClassLoader) {
        // 先挂 AOSP 的设置首页，再退到 preference 基类兜底（各家 ROM 可能改名或自定义 fragment）。
        // 注意：Xposed 只拦「真正执行的那个方法」，所以要从具体类往父类找**第一个**声明处，
        // 直接挂基类会因为子类覆写而永远不触发。
        val candidates = listOf(
            "com.android.settings.homepage.TopLevelSettings",
            "com.android.settings.homepage.SettingsHomeFragment",
            "androidx.preference.PreferenceFragmentCompat",
            "android.preference.PreferenceFragment",
        )
        var hooked = false
        candidates.forEach { name ->
            val cls = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull() ?: return@forEach
            hooked = hookDeclared(cl, cls, "onCreatePreferences", PREFERENCES_ARGS) || hooked
            hooked = hookDeclared(cl, cls, "onResume", NO_ARGS) || hooked
        }
        XposedBridge.log(
            TAG + (if (hooked) " 已挂上系统设置首页" else " 这个 ROM 没找到可挂的 fragment，入口注入不可用"),
        )
    }

    private val injectHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val fragment = param.thisObject ?: return
            // onResume 会挂到公共父类上，设置里每个 fragment 都会路过，这里先筛掉非首页的
            if (!isHomeFragment(fragment)) return
            runCatching { inject(fragment) }
                .onFailure { XposedBridge.log("$TAG 注入失败：$it") }
        }
    }

    /** 沿继承链找第一个声明了 [method] 的类并挂钩；返回是否真的挂上了。 */
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
            if (declared != null) {
                runCatching { XposedBridge.hookMethod(declared, injectHook) }
                return true
            }
            cls = cls.superclass
        }
        return false
    }

    /** 只有设置首页那类 fragment 才该被插入口。 */
    private fun isHomeFragment(fragment: Any): Boolean {
        val name = fragment.javaClass.name
        return name.contains("TopLevelSettings") ||
            name.endsWith("SettingsHomeFragment") ||
            (name.contains(".homepage.") && !name.contains("Activity"))
    }

    // ------------------------------------------------------------------ 注入

    private fun inject(fragment: Any) {
        // 设置首页 fragment 还没建出 PreferenceScreen（或不是 preference 页面）时直接走人
        val screen = runCatching { XposedHelpers.callMethod(fragment, "getPreferenceScreen") }
            .getOrNull() ?: return
        val context = contextOf(fragment) ?: return

        val cfg = config()
        if (!cfg.entry) return
        // 幂等：同一页上已经有我们的入口，别再插第二个
        if (runCatching { XposedHelpers.callMethod(screen, "findPreference", ENTRY_KEY) }
                .getOrNull() != null
        ) {
            return
        }
        // 应用卸了就别插，免得留个点不动的死入口
        val pm = context.packageManager
        val appInfo = runCatching { pm.getApplicationInfo(APP_PKG, 0) }.getOrNull() ?: return
        val title = pm.getApplicationLabel(appInfo).toString()
        val summary = appString(context, "set_hook_settings_entry_summary")
        val launch = launchIntent(context)
        val icon = runCatching { entryIcon(context, cfg.iconStyle) }.getOrNull()

        val prefCls = pickPrefClass() ?: return
        val pref = XposedHelpers.newInstance(prefCls, context)
        runCatching { XposedHelpers.callMethod(pref, "setKey", ENTRY_KEY) }
        runCatching { XposedHelpers.callMethod(pref, "setTitle", title) }
        summary?.let { runCatching { XposedHelpers.callMethod(pref, "setSummary", it) } }
        icon?.let { runCatching { XposedHelpers.callMethod(pref, "setIcon", it) } }
        runCatching { XposedHelpers.callMethod(pref, "setPersistent", false) }
        runCatching { XposedHelpers.callMethod(pref, "setIntent", launch) }

        //「归入同一栏目」关掉时给它单独开一个栏目，否则直接混进首页现有列表
        val target = if (cfg.sameGroup) pref
        else wrapInCategory(context, pref, title) ?: pref

        insertAt(fragment, screen, target, cfg.position)
    }

    /** Preference / PreferenceCategory 都要宿主自己的类，跨包类型对不上会直接 ClassCastException。 */
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
     *
     * 不能只靠 `setOrder`：首页是按「加进来的顺序」渲染的，改 order 不保证重排，
     * 直接动 `mPreferenceList` 在 androidx 与框架两版 Preference 上字段名一致、最稳。
     * 分组栏（PreferenceGroupAdapter）已经画出来的话，要把它的可见表一起挪再刷新。
     */
    private fun insertAt(fragment: Any, screen: Any, target: Any, position: String) {
        val added = runCatching { XposedHelpers.callMethod(screen, "addPreference", target) as Boolean }
            .getOrDefault(false)
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
        notifyList(fragment)
    }

    private fun groupList(screen: Any): MutableList<Any>? =
        runCatching {
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

    /** 把 [target] 挪到 [toIndex]；列表里还没有它（分组栏还没刷新）就直接插进去。 */
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

    private fun notifyList(fragment: Any) {
        val listView = runCatching { XposedHelpers.callMethod(fragment, "getListView") }.getOrNull() ?: return
        val adapter = runCatching { XposedHelpers.callMethod(listView, "getAdapter") }.getOrNull() ?: return
        runCatching { XposedHelpers.callMethod(adapter, "notifyDataSetChanged") }
    }

    private fun contextOf(fragment: Any): Context? =
        runCatching { XposedHelpers.callMethod(fragment, "requireContext") as Context }.getOrNull()
            ?: runCatching { XposedHelpers.callMethod(fragment, "getActivity") as? Context }.getOrNull()

    // -------------------------------------------------------------- 入口样式

    private fun launchIntent(context: Context): Intent {
        // 首选：LAUNCHER intent（顺带拿到正确的 launcher activity）
        runCatching { context.packageManager.getLaunchIntentForPackage(APP_PKG) }.getOrNull()
            ?.let { return it.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } }
        // 兜底：包可见性查不到本应用时，按已知组件名直接拉起
        return Intent().apply {
            component = ComponentName(APP_PKG, "$APP_PKG.$MAIN_ACTIVITY")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun entryIcon(context: Context, style: String): Drawable? {
        val base = runCatching { context.packageManager.getApplicationIcon(APP_PKG) }.getOrNull()
            ?: return null
        if (style != ICON_OUTLINE) return base
        // 描边样式：外面套一圈圆角描边，颜色跟设置页底色走，深浅主题都看得见
        val density = context.resources.displayMetrics.density
        return LayerDrawable(
            arrayOf(
                base,
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = density * 8f
                    setStroke((density * 1.5f).toInt().coerceAtLeast(2), outlineStroke(context))
                },
            ),
        )
    }

    private fun outlineStroke(context: Context): Int {
        val v = TypedValue()
        val bg = if (context.theme.resolveAttribute(android.R.attr.colorBackground, v, true) &&
            v.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
        ) {
            v.data
        } else {
            0xFFFFFFFF.toInt()
        }
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
        const val POSITION_BOTTOM = "bottom"
        const val ICON_DEFAULT = "default"
        const val ICON_OUTLINE = "outline"

        const val ENTRY_KEY = "yzliyin_settings_entry"

        const val PREF_ANDROIDX = "androidx.preference.Preference"
        const val PREF_FRAMEWORK = "android.preference.Preference"
        const val CATEGORY_ANDROIDX = "androidx.preference.PreferenceCategory"
        const val CATEGORY_FRAMEWORK = "android.preference.PreferenceCategory"

        const val CONFIG_TTL_MS = 5_000L

        val NO_ARGS = emptyArray<String>()
        val PREFERENCES_ARGS = arrayOf("android.os.Bundle", "java.lang.String")
    }
}
