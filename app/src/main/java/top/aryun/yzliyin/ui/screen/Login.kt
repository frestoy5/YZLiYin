package top.aryun.yzliyin.ui.screen

import android.graphics.Bitmap
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.core.net.NeteaseQrLoginClient
import top.aryun.yzliyin.core.net.NeteaseQrLoginSession
import top.aryun.yzliyin.core.net.YouTubeApi
import top.aryun.yzliyin.core.model.QrStatus
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.youtube.auth.YouTubeAuthBundle
import top.aryun.yzliyin.youtube.auth.YouTubeCookieSupport
import top.aryun.yzliyin.youtube.auth.parseYouTubeAuthBundleFromRaw

/**
 * 登录页：按 method 路由。
 *
 *  - `netease_web`     网易云网页登录（WebView 桌面 UA → MUSIC_U）
 *  - `netease_captcha` 网易云验证码登录
 *  - `netease_qr`      网易云扫码登录（移植自 NeriPlayer）
 *  - `kugou_qr`        酷狗扫码登录
 *  - `bilibili_qr`     B 站扫码登录
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(nav: NavController, method: String = Routes.LOGIN_NETEASE_WEB) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    when (method) {
                        Routes.LOGIN_NETEASE_CAPTCHA -> stringResource(R.string.lgn_title_captcha)
                        Routes.LOGIN_NETEASE_WEB -> stringResource(R.string.lgn_title_netease)
                        Routes.LOGIN_KUGOU_QR -> stringResource(R.string.lgn_title_kugou)
                        Routes.LOGIN_BILIBILI_QR -> stringResource(R.string.lgn_title_bili)
                        Routes.LOGIN_YOUTUBE_WEB -> stringResource(R.string.lgn_title_youtube)
                        else -> stringResource(R.string.lgn_title_qr)
                    },
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.lgn_back))
                }
            },
        )

        when (method) {
            Routes.LOGIN_NETEASE_WEB -> NeteaseWebLoginContent(nav)
            Routes.LOGIN_NETEASE_CAPTCHA -> NeteaseCaptchaLoginContent(onDone = { nav.popBackStack() })
            Routes.LOGIN_KUGOU_QR -> KugouQrLoginContent(onDone = { nav.popBackStack() })
            Routes.LOGIN_BILIBILI_QR -> BiliQrLoginContent(onDone = { nav.popBackStack() })
            Routes.LOGIN_YOUTUBE_WEB -> YouTubeLoginContent(nav)
            else -> NeteaseQrLoginContent(onDone = { nav.popBackStack() })
        }
    }
}

// ================= 网页登录（网易云） =================

private const val NETEASE_LOGIN_URL = "https://music.163.com/"
private val NETEASE_COOKIE_URLS = listOf(
    "https://music.163.com",
    "https://interface.music.163.com",
    "https://interface3.music.163.com",
)
private const val NETEASE_DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

@Composable
private fun NeteaseWebLoginContent(nav: NavController) {
    WebLoginContent(
        nav = nav,
        startUrl = NETEASE_LOGIN_URL,
        cookieUrls = NETEASE_COOKIE_URLS,
        clearKeys = listOf("MUSIC_U", "__csrf", "__remember_me"),
        requiredKey = "MUSIC_U",
        desktopUa = true,
        hint = stringResource(R.string.lgn_netease_web_hint),
        onSave = { cookies ->
            AppGraph.store.saveNeteaseCookies(cookies)
            NeteaseApi.syncCookies()
            runCatching { NeteaseApi.refreshAccount() }
        },
    )
}

// ================= 网页登录（YouTube / Google 账号） =================

private const val YOUTUBE_LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F"
/** 「电脑模式」用的桌面 Chrome UA（页面渲染成桌面版，排版与电脑一致）。 */
private const val YOUTUBE_DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"
private val YOUTUBE_COOKIE_URLS = listOf(
    "https://music.youtube.com",
    "https://www.youtube.com",
    "https://accounts.google.com",
)

/**
 * YouTube 登录（网页兜底）：WebView 打开 Google 登录页，轮询 CookieManager 里的身份 Cookie
 * （SAPISID / __Secure-3PAPISID 等，判定复用移植栈的 [YouTubeCookieSupport.isLoggedIn]），
 * 命中即组装 [YouTubeAuthBundle] 存进加密仓库。
 *
 * 右上角工具条提供：**电脑模式**（切桌面 Chrome UA 按桌面版渲染，页面卡住/排版不适时用）、
 * **刷新**（重载当前页）、**粘贴 Cookie**（WebView 被 Google 拒绝时，把浏览器里
 * music.youtube.com 的请求 Cookie 整段贴进来兜底）。
 */
@Composable
private fun YouTubeLoginContent(nav: NavController) {
    val context = LocalContext.current
    val holder = remember { arrayOfNulls<WebView>(1) }
    var loading by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    // ⚠ remember 的 lambda 不是 @Composable 上下文，stringResource 必须在外面解析好再喂进去
    val googleWebHint = stringResource(R.string.lgn_google_web_hint)
    var status by remember { mutableStateOf(googleWebHint) }
    var done by remember { mutableStateOf(false) }
    var pasteMode by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    /** 电脑模式：把 WebView UA 换成桌面 Chrome，页面按桌面版渲染。 */
    var desktopMode by remember { mutableStateOf(false) }
    /** 保存是否真的成功（saveAuth 后回读校验，避免「页面登录了但落盘失败」时空报成功）。 */
    var saveOk by remember { mutableStateOf(false) }
    /** WebView 的出厂 UA（退出电脑模式时还原用）。 */
    val defaultUaHolder = remember { arrayOf("") }

    fun readCookies(): Map<String, String> {
        val cm = CookieManager.getInstance()
        val out = linkedMapOf<String, String>()
        YOUTUBE_COOKIE_URLS.forEach { url ->
            cm.getCookie(url)?.split(";")?.forEach { pair ->
                val i = pair.indexOf('=')
                if (i > 0) out[pair.substring(0, i).trim()] = pair.substring(i + 1).trim()
            }
        }
        return out
    }

    Column(Modifier.fillMaxSize()) {
        if (pasteMode) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.lgn_paste_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = {
                        pasteText = it
                        pasteError = null
                    },
                    placeholder = { Text("SAPISID=...; __Secure-3PAPISID=...; ...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                )
                pasteError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { pasteMode = false }) { Text(stringResource(R.string.lgn_back_to_web)) }
                    Button(
                        onClick = {
                            val bundle = parseYouTubeAuthBundleFromRaw(pasteText.trim())
                            if (bundle != null && bundle.hasLoginCookies()) {
                                YouTubeApi.saveAuth(bundle.normalized(savedAt = System.currentTimeMillis()))
                                if (YouTubeApi.isLoggedIn()) {
                                    status = context.getString(R.string.lgn_login_success_syncing)
                                    saveOk = true
                                    done = true
                                } else {
                                    pasteError = context.getString(R.string.lgn_paste_save_fail)
                                }
                            } else {
                                pasteError = context.getString(R.string.lgn_paste_invalid)
                            }
                        },
                    ) { Text(stringResource(R.string.lgn_sign_in)) }
                }
            }
        } else {
            // 右上角工具条：状态提示 + 电脑模式 + 刷新 + 粘贴 Cookie 兜底
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    status,
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    desktopMode = !desktopMode
                    holder[0]?.settings?.let { s ->
                        s.userAgentString =
                            if (desktopMode) YOUTUBE_DESKTOP_UA
                            else defaultUaHolder[0].ifBlank { null }
                    }
                    holder[0]?.reload()
                }) {
                    Text(
                        if (desktopMode) stringResource(R.string.lgn_desktop_mode_off)
                        else stringResource(R.string.lgn_desktop_mode),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (desktopMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { holder[0]?.reload() }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.lgn_refresh),
                        modifier = Modifier.size(20.dp),
                    )
                }
                TextButton(onClick = { pasteMode = true }) {
                    Text(stringResource(R.string.lgn_paste_cookie), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (loading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            holder[0] = this
                            defaultUaHolder[0] = settings.userAgentString.orEmpty()
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                // 保持默认移动 Chrome UA：Google 会拒绝「桌面前缀 UA 的 Android WebView」
                            }
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                @Deprecated("Deprecated in Java")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    val scheme = android.net.Uri.parse(url.orEmpty()).scheme?.lowercase()
                                    return scheme != "http" && scheme != "https"
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    loading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    loading = false
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    super.onReceivedError(view, request, error)
                                    if (request?.isForMainFrame == true) {
                                        status = context.getString(
                                            R.string.lgn_page_load_fail,
                                            error?.description
                                                ?: context.getString(R.string.lgn_network_error),
                                        )
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                    if (newProgress >= 100) loading = false
                                }
                            }
                            loadUrl(YOUTUBE_LOGIN_URL)
                        }
                    },
                    onRelease = { wv ->
                        wv.stopLoading()
                        wv.destroy()
                        holder[0] = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // 轮询 Cookie：拿到身份 Cookie（SAPISID 等）即视为登录成功
    LaunchedEffect(Unit) {
        while (!done) {
            delay(1500)
            val cookies = withContext(Dispatchers.IO) { readCookies() }
            if (YouTubeCookieSupport.isLoggedIn(cookies)) {
                val ua = withContext(Dispatchers.Main) { holder[0]?.settings?.userAgentString.orEmpty() }
                val saved = withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    YouTubeApi.saveAuth(
                        YouTubeAuthBundle(
                            cookies = LinkedHashMap(cookies),
                            cookieHeader = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" },
                            userAgent = ua,
                            savedAt = now,
                        ).normalized(savedAt = now),
                    )
                    YouTubeApi.isLoggedIn()
                }
                if (saved) {
                    status = context.getString(R.string.lgn_login_success_syncing)
                    saveOk = true
                    done = true
                } else {
                    // 页面明明登录了却存不进去：不再谎报成功，让用户看到真实状态
                    status = context.getString(R.string.lgn_page_login_save_fail)
                }
            }
        }
    }

    LaunchedEffect(done) {
        if (done && saveOk) {
            delay(500)
            nav.popBackStack()
        }
    }

    BackHandler {
        val wv = holder[0]
        if (!pasteMode && wv?.canGoBack() == true) wv.goBack() else nav.popBackStack()
    }
}

/**
 * 通用 WebView 登录：加载登录页 → 轮询 CookieManager → 命中 [requiredKey] 即落库并返回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebLoginContent(
    nav: NavController,
    startUrl: String,
    cookieUrls: List<String>,
    clearKeys: List<String>,
    requiredKey: String,
    hint: String,
    desktopUa: Boolean = false,
    onSave: suspend (Map<String, String>) -> Unit,
) {
    val context = LocalContext.current
    val holder = remember { arrayOfNulls<WebView>(1) }
    var loading by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(hint) }
    var done by remember { mutableStateOf(false) }

    fun readCookies(): Map<String, String> {
        val cm = CookieManager.getInstance()
        val out = linkedMapOf<String, String>()
        cookieUrls.forEach { url ->
            cm.getCookie(url)?.split(";")?.forEach { pair ->
                val i = pair.indexOf('=')
                if (i > 0) out[pair.substring(0, i).trim()] = pair.substring(i + 1).trim()
            }
        }
        return out
    }

    Column(Modifier.fillMaxSize()) {
        if (loading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        holder[0] = this
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            allowFileAccess = false
                            allowContentAccess = false
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            if (desktopUa) userAgentString = NETEASE_DESKTOP_UA
                        }
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        // 清掉本平台旧登录态，保证拿到的是一次全新的登录
                        clearKeys.forEach { key ->
                            cookieUrls.forEach { url -> expireCookie(cm, url, key, null) }
                            cookieUrls.forEach { url -> expireCookie(cm, url, key, ".music.163.com") }
                        }
                        cm.flush()

                        webViewClient = object : WebViewClient() {
                            @Deprecated("Deprecated in Java")
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                val scheme = android.net.Uri.parse(url.orEmpty()).scheme?.lowercase()
                                return scheme != "http" && scheme != "https"
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                loading = false
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                                if (newProgress >= 100) loading = false
                            }
                        }
                        loadUrl(startUrl)
                    }
                },
                onRelease = { wv ->
                    wv.stopLoading()
                    wv.destroy()
                    holder[0] = null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            status,
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 轮询 Cookie：命中登录凭证即视为成功
    LaunchedEffect(Unit) {
        while (!done) {
            delay(1200)
            val cookies = withContext(Dispatchers.IO) { readCookies() }
            if (!cookies[requiredKey].isNullOrBlank()) {
                done = true
                status = context.getString(R.string.lgn_login_success_syncing)
                runCatching { onSave(cookies) }
                delay(300)
                nav.popBackStack()
                return@LaunchedEffect
            }
        }
    }

    BackHandler {
        val wv = holder[0]
        if (wv?.canGoBack() == true) wv.goBack() else nav.popBackStack()
    }
}

private fun expireCookie(cm: CookieManager, url: String, key: String, domain: String?) {
    val domainPart = domain?.let { "; Domain=$it" }.orEmpty()
    cm.setCookie(
        url,
        "$key=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0$domainPart; Path=/; Secure",
    )
}

// ================= 网易云验证码登录 =================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeteaseCaptchaLoginContent(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mobile by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var loggingIn by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var tip by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.lgn_phone_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = mobile,
            onValueChange = { if (it.length <= 11) mobile = it.filter { c -> c.isDigit() } },
            label = { Text(stringResource(R.string.lgn_phone_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { if (it.length <= 8) code = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.lgn_code_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = {
                    if (mobile.length != 11) {
                        tip = context.getString(R.string.lgn_phone_invalid)
                        return@OutlinedButton
                    }
                    sending = true
                    scope.launch {
                        NeteaseApi.sendCaptcha(mobile)
                            .onSuccess { tip = it; countdown = 60 }
                            .onFailure { tip = it.message ?: context.getString(R.string.lgn_send_fail) }
                        sending = false
                    }
                },
                enabled = countdown == 0 && !sending,
            ) {
                Text(
                    if (countdown > 0) stringResource(R.string.lgn_send_countdown, countdown)
                    else if (sending) stringResource(R.string.lgn_sending)
                    else stringResource(R.string.lgn_get_code),
                )
            }
        }

        tip?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                if (mobile.length != 11) {
                    tip = context.getString(R.string.lgn_phone_invalid)
                    return@Button
                }
                if (code.length < 4) {
                    tip = context.getString(R.string.lgn_code_required)
                    return@Button
                }
                loggingIn = true
                scope.launch {
                    NeteaseApi.loginByCaptcha(mobile, code)
                        .onSuccess {
                            tip = context.getString(R.string.lgn_login_success)
                            onDone()
                        }
                        .onFailure { tip = it.message ?: context.getString(R.string.lgn_login_fail) }
                    loggingIn = false
                }
            },
            enabled = !loggingIn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loggingIn) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.lgn_sign_in))
            }
        }

        Text(
            stringResource(R.string.lgn_captcha_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ================= 扫码登录（三源共用） =================

/**
 * 扫码登录通用壳：拉二维码内容 → 渲染 → 1.5s 轮询，成功或失败即停止。
 * 各源的差异都在 [createContent] 与 [check] 里；[check] 自行完成落库等副作用。
 */
@Composable
private fun QrLoginContent(
    title: String,
    subtitle: String,
    waitingText: String,
    scannedText: String,
    createContent: suspend () -> String,
    check: suspend () -> QrStatus,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<QrStatus>(QrStatus.Waiting) }
    var tick by remember { mutableLongStateOf(0L) }
    val qrSizePx = with(LocalDensity.current) { 220.dp.roundToPx() }

    suspend fun refresh() {
        loading = true
        status = QrStatus.Waiting
        qrBitmap = null
        val content = runCatching { createContent() }
            .onFailure { status = QrStatus.Failed(it.message ?: context.getString(R.string.lgn_qr_fetch_fail)) }
            .getOrDefault("")
        if (content.isNotBlank()) {
            qrBitmap = runCatching {
                withContext(Dispatchers.Default) { createQrBitmap(content, qrSizePx) }
            }.getOrNull()
        }
        loading = false
        tick++
    }

    LaunchedEffect(Unit) { refresh() }

    LaunchedEffect(tick) {
        if (tick == 0L) return@LaunchedEffect
        repeat(120) {
            val next = runCatching { check() }
                .getOrElse {
                    status = QrStatus.Failed(it.message ?: context.getString(R.string.lgn_qr_poll_fail))
                    return@LaunchedEffect
                }
            status = next
            when (next) {
                is QrStatus.Success -> {
                    delay(400)
                    onDone()
                    return@LaunchedEffect
                }
                is QrStatus.Failed -> return@LaunchedEffect
                else -> delay(1500)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        androidx.compose.material3.Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.size(240.dp),
        ) {
            when {
                loading -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                qrBitmap == null -> Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.lgn_qr_render_fail),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                else -> androidx.compose.foundation.Image(
                    bitmap = qrBitmap!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.lgn_qr_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
        }

        val statusText = when (val s = status) {
            is QrStatus.Waiting -> waitingText
            is QrStatus.Scanned -> scannedText
            is QrStatus.Failed -> s.reason
            is QrStatus.Success -> stringResource(R.string.lgn_login_success)
        }
        Text(
            statusText,
            color = when (status) {
                is QrStatus.Failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        if (status is QrStatus.Failed) {
            OutlinedButton(onClick = { scope.launch { refresh() } }) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.lgn_qr_refresh))
            }
        }
    }
}

/** 网易云扫码登录：移植自 NeriPlayer 的 NeteaseQrLoginActivity，确认（803）后落库 Cookie。 */
@Composable
private fun NeteaseQrLoginContent(onDone: () -> Unit) {
    val context = LocalContext.current
    val client = remember { NeteaseQrLoginClient(context) }
    var session by remember { mutableStateOf<NeteaseQrLoginSession?>(null) }

    QrLoginContent(
        title = stringResource(R.string.lgn_qr_netease_title),
        subtitle = stringResource(R.string.lgn_qr_netease_subtitle),
        waitingText = stringResource(R.string.lgn_qr_netease_waiting),
        scannedText = stringResource(R.string.lgn_qr_scanned),
        createContent = {
            client.reset()
            val created = withContext(Dispatchers.IO) { client.createSession() }
            session = created
            created.qrContent
        },
        check = {
            val current = session
            if (current == null) {
                QrStatus.Waiting
            } else {
                val result = withContext(Dispatchers.IO) { client.checkLogin(current) }
                when (result.code) {
                    803 -> {
                        val cookies = result.cookies
                        if (cookies["MUSIC_U"].isNullOrBlank()) {
                            QrStatus.Failed(context.getString(R.string.lgn_qr_cookie_incomplete))
                        } else {
                            AppGraph.store.saveNeteaseCookies(cookies)
                            NeteaseApi.syncCookies()
                            runCatching { NeteaseApi.refreshAccount() }
                            QrStatus.Success(AppGraph.store.neteaseName)
                        }
                    }
                    802 -> QrStatus.Scanned
                    801 -> QrStatus.Waiting
                    800 -> QrStatus.Failed(context.getString(R.string.lgn_qr_expired))
                    else -> QrStatus.Failed(result.message.ifBlank { "code=${result.code}" })
                }
            }
        },
        onDone = onDone,
    )
}

private const val KUGOU_QR_URL_PREFIX = "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode="

/** 酷狗扫码登录。 */
@Composable
private fun KugouQrLoginContent(onDone: () -> Unit) {
    var key by remember { mutableStateOf("") }

    QrLoginContent(
        title = stringResource(R.string.lgn_qr_kugou_title),
        subtitle = stringResource(R.string.lgn_qr_kugou_subtitle),
        waitingText = stringResource(R.string.lgn_qr_kugou_waiting),
        scannedText = stringResource(R.string.lgn_qr_kugou_scanned),
        createContent = {
            val created = KugouApi.createQrKey()
            key = created
            if (created.isBlank()) "" else KUGOU_QR_URL_PREFIX + created
        },
        check = { if (key.isBlank()) QrStatus.Waiting else KugouApi.checkQr(key) },
        onDone = onDone,
    )
}

/** B 站扫码登录（`checkQr` 内部使用最近一次申请到的 qrcode_key）。 */
@Composable
private fun BiliQrLoginContent(onDone: () -> Unit) {
    QrLoginContent(
        title = stringResource(R.string.lgn_qr_bili_title),
        subtitle = stringResource(R.string.lgn_qr_bili_subtitle),
        waitingText = stringResource(R.string.lgn_qr_bili_waiting),
        scannedText = stringResource(R.string.lgn_qr_scanned),
        createContent = { BiliApi.createQrContent() },
        check = { BiliApi.checkQr() },
        onDone = onDone,
    )
}

/** 用 zxing 生成二维码位图（内容取 session.qrContent）。 */
private fun createQrBitmap(content: String, sizePx: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val rowOffset = y * sizePx
        for (x in 0 until sizePx) {
            pixels[rowOffset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    }
}
