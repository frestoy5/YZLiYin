package top.aryun.yzliyin.util

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import top.aryun.yzliyin.core.data.SessionStore
import java.util.Locale

/**
 * 界面语言：设置 → 主题 里的「跟随系统 / 中文 / 日文 / English」。
 *
 * 做法是**每次进 Activity 都在 attachBaseContext 里重包一次 Context**，而不是走
 * 系统的 per-app language（Android 13 才有，还要 appcompat 才能落库）：
 * 这样 minSdk 26 全覆盖，也不存在「系统语言设置」和「App 内设置」两套真相打架——
 * 选了具体语言就重包，选跟随系统就原样返回，让系统（含 Android 13 给本 App
 * 单独设的语言）自己决定。
 *
 * 资源侧：`values/` 是中文（默认，也是本 App 的原生语言），
 * `values-ja` / `values-en` 各自覆盖；没翻译到的 key 会回落到中文，不会出现空白。
 *
 * 除了包 Activity，还有两条取文案的路：
 *  1. **Application 也包一层**（见 `YzliyinApp.attachBaseContext`）——通知栏、后台 Service
 *     拿的都是 applicationContext，不包就会一直显示系统语言。
 *  2. **[string]** 给 core/net 这类**没有 Compose 上下文**的深层代码用，
 *     它按当前语言现包一个 Context 取文案，所以切换语言后立刻正确，
 *     不受「Application 已经在启动时包过一次」的影响。
 */
object AppLocale {

    /** 跟随系统（不重包 Context）。 */
    const val SYSTEM = "system"

    const val ZH = "zh"
    const val JA = "ja"
    const val EN = "en"

    /**
     * 进程启动时的系统默认 Locale：切回「跟随系统」时要能还原回它，
     * 不能沿用上一次手动选择留下的值。在第一次进入 [wrap] 之前初始化，
     * 所以拿到的一定是真正的系统语言。
     */
    private val systemLocale: Locale = Locale.getDefault()

    /**
     * **未套语言**的 Application 基线 Context，由 [init] 登记。
     * 存的是没包装过的原始 Context，切换语言时从它重新包，避免在已包装的
     * Context 上反复叠包导致配置层层累积。
     */
    @Volatile
    private var base: Context? = null

    /** 当前界面语言（[SYSTEM] 表示跟随系统）。 */
    @Volatile
    private var lang: String = SYSTEM

    /** 只读对外暴露：`Application.attachBaseContext` 要用它把语言传给 [wrap]。 */
    val currentLanguage: String
        get() = lang

    @Volatile
    private var cachedLocalized: Context? = null

    @Volatile
    private var cachedLocalizedLang: String? = null

    /**
     * 由 `Application.attachBaseContext` 调用（进程里最早能拿到 Context 的地方，
     * 早于 AppGraph 初始化）：登记基线 Context 并读出持久化的语言。
     */
    fun init(baseContext: Context) {
        base = baseContext
        lang = SessionStore.readLanguage(baseContext)
        cachedLocalized = null
        cachedLocalizedLang = null
    }

    /** 设置页切换语言后调用，让 [string] 立刻按新语言取文案。 */
    fun setLanguage(value: String) {
        if (lang == value) return
        lang = value
        cachedLocalized = null
        cachedLocalizedLang = null
    }

    /**
     * 把 Context 换成指定语言的版本；[lang] 是 [SYSTEM] 或不认识的值时原样返回。
     *
     * 同时改掉进程默认 Locale，让 `String.format`、日期/数字格式化这类
     * **不走资源**的场景也跟着变，否则会出现「标题是日文、格式化数字是中文」。
     */
    fun wrap(context: Context, lang: String): Context {
        val locale = localeFor(lang) ?: run {
            // 跟随系统：把默认 Locale 还原，避免上次手动选择残留
            Locale.setDefault(systemLocale)
            return context
        }
        Locale.setDefault(locale)
        return context.createConfigurationContext(localeOnly(context, locale))
    }

    /**
     * 只改语言的 Configuration —— 这是整个方案里最容易踩坑的地方。
     *
     * **不能**拷贝 `context.resources.configuration` 再 setLocale：那份拷贝把
     * `screenWidthDp` / `screenHeightDp` / `orientation` 等尺寸字段也带了进来，
     * 而 `createConfigurationContext` 会把传进去的**每一个有值的字段**都当成
     * 永久 override——文档原话：「基础配置变化（例如旋转）时，本 Context 的资源
     * 也会跟着变，**除非该值在这里被显式覆盖过**」。整份拷贝 = 尺寸全被显式覆盖 =
     * 启动那一刻的宽度被永久冻住：平板旋转后 `screenWidthDp` 不再更新，
     * 宽屏布局（左侧导航 / 播放页两栏）再也回不来，界面永远停在启动时的形态——
     * 手机上宽度本来就 <600dp，看不出差别；平板上直接退回手机样式。
     *
     * 所以从零构造：尺寸字段全部 UNDEFINED（跟随基础配置实时更新），
     * 只显式带上语言，外加把系统字号缩放保住（fresh 默认是 1，会把
     * 系统「字体大小」设置顶掉）。fontScale 变更会重建 Activity、重新走一遍
     * [wrap]，不存在快照过期的问题。
     */
    private fun localeOnly(context: Context, locale: Locale): Configuration =
        Configuration().apply {
            fontScale = context.resources.configuration.fontScale
            setLocale(locale)
            setLayoutDirection(locale)
        }

    /** 语言代码 → Locale；[SYSTEM] 与未知值返回 null（表示不干预）。 */
    fun localeFor(lang: String): Locale? = when (lang) {
        ZH -> Locale.SIMPLIFIED_CHINESE
        JA -> Locale.JAPANESE
        EN -> Locale.ENGLISH
        else -> null
    }

    /**
     * 按**当前界面语言**解析的 Context（带缓存）。
     * 跟随系统时直接用基线——基线本来就是系统语言。
     */
    private fun localized(): Context? {
        val b = base ?: return null
        val target = lang
        if (target == SYSTEM || localeFor(target) == null) return b
        if (target == cachedLocalizedLang) return cachedLocalized
        val locale = localeFor(target) ?: return b
        // 与 [wrap] 同理：只覆盖语言字段，尺寸等交给基础配置
        val ctx = b.createConfigurationContext(
            localeOnly(b, locale),
        )
        cachedLocalized = ctx
        cachedLocalizedLang = target
        return ctx
    }

    /**
     * 取本地化文案，给**没有 Compose 上下文**的深层代码用
     * （core/net 的错误提示、模型里带的说明文字等）。
     *
     * Composable 里请直接用 `stringResource()`，不要绕到这里。
     * [init] 一定在 `Application.attachBaseContext` 里调过，[base] 不会为空；
     * 兜底返回空串只为万一——空串比抛异常打断错误提示更安全。
     */
    fun string(@StringRes id: Int): String = localized()?.getString(id).orEmpty()

    /** 同 [string]，带参数；参数一律按 `%1$s` 占位，见迁移约定。 */
    fun string(@StringRes id: Int, vararg args: Any): String =
        localized()?.getString(id, *args).orEmpty()
}
