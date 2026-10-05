package top.aryun.yzliyin.ui.screen

import android.graphics.Bitmap
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.core.net.NeteaseQrLoginClient
import top.aryun.yzliyin.core.net.NeteaseQrLoginSession
import top.aryun.yzliyin.core.model.QrStatus
import top.aryun.yzliyin.ui.Routes

/**
 * 登录页：按 method 路由。
 *
 *  - `netease_web`     网易云网页登录（WebView 桌面 UA → MUSIC_U）
 *  - `netease_captcha` 网易云验证码登录
 *  - `netease_qr`      网易云扫码登录（移植自 NeriPlayer）
 *
 * 酷狗与哔哩哔哩登录入口已按需求移除，数据源仅网易云音乐。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(nav: NavController, method: String = Routes.LOGIN_NETEASE_WEB) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    when (method) {
                        Routes.LOGIN_NETEASE_CAPTCHA -> "验证码登录"
                        Routes.LOGIN_NETEASE_WEB -> "网易云登录"
                        else -> "扫码登录"
                    },
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        when (method) {
            Routes.LOGIN_NETEASE_WEB -> NeteaseWebLoginContent(nav)
            Routes.LOGIN_NETEASE_CAPTCHA -> NeteaseCaptchaLoginContent(onDone = { nav.popBackStack() })
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
        hint = "请在下方页面完成网易云音乐登录",
        onSave = { cookies ->
            AppGraph.store.saveNeteaseCookies(cookies)
            NeteaseApi.syncCookies()
            runCatching { NeteaseApi.refreshAccount() }
        },
    )
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
                status = "登录成功，正在同步账号…"
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
        Text("手机号验证码登录", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = mobile,
            onValueChange = { if (it.length <= 11) mobile = it.filter { c -> c.isDigit() } },
            label = { Text("手机号") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { if (it.length <= 8) code = it.filter { c -> c.isDigit() } },
                label = { Text("验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = {
                    if (mobile.length != 11) {
                        tip = "请输入 11 位手机号"
                        return@OutlinedButton
                    }
                    sending = true
                    scope.launch {
                        NeteaseApi.sendCaptcha(mobile)
                            .onSuccess { tip = it; countdown = 60 }
                            .onFailure { tip = it.message ?: "验证码发送失败" }
                        sending = false
                    }
                },
                enabled = countdown == 0 && !sending,
            ) {
                Text(if (countdown > 0) "${countdown}s" else if (sending) "发送中" else "获取验证码")
            }
        }

        tip?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                if (mobile.length != 11) {
                    tip = "请输入 11 位手机号"
                    return@Button
                }
                if (code.length < 4) {
                    tip = "请输入验证码"
                    return@Button
                }
                loggingIn = true
                scope.launch {
                    NeteaseApi.loginByCaptcha(mobile, code)
                        .onSuccess {
                            tip = "登录成功"
                            onDone()
                        }
                        .onFailure { tip = it.message ?: "登录失败" }
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
                Text("登录")
            }
        }

        Text(
            "验证码由网易云音乐发送；登录后可播放会员歌曲。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ================= 网易云扫码登录 =================

/**
 * 网易云扫码登录：移植自 NeriPlayer 的 NeteaseQrLoginActivity。
 * 二维码内容为 session.qrContent，1.5s 轮询一次，确认（803）后落库 Cookie 并同步账号信息。
 */
@Composable
private fun NeteaseQrLoginContent(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { NeteaseQrLoginClient(context) }
    var session by remember { mutableStateOf<NeteaseQrLoginSession?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<QrStatus>(QrStatus.Waiting) }
    var tick by remember { mutableLongStateOf(0L) }
    val qrSizePx = with(LocalDensity.current) { 220.dp.roundToPx() }

    suspend fun refresh() {
        loading = true
        status = QrStatus.Waiting
        session = null
        qrBitmap = null
        client.reset()
        runCatching { withContext(Dispatchers.IO) { client.createSession() } }
            .onSuccess { s ->
                session = s
                qrBitmap = runCatching {
                    withContext(Dispatchers.Default) { createQrBitmap(s.qrContent, qrSizePx) }
                }.getOrNull()
            }
            .onFailure { status = QrStatus.Failed(it.message ?: "二维码获取失败") }
        loading = false
        tick++
    }

    LaunchedEffect(Unit) { refresh() }

    LaunchedEffect(tick) {
        val s = session ?: return@LaunchedEffect
        // 轮询：801 等待扫码 / 802 已扫待确认 / 803 成功 / 800 过期
        repeat(120) {
            val check = runCatching {
                withContext(Dispatchers.IO) { client.checkLogin(s) }
            }.getOrElse { status = QrStatus.Failed(it.message ?: "轮询失败"); return@LaunchedEffect }
            when (check.code) {
                803 -> {
                    val cookies = check.cookies
                    if (cookies["MUSIC_U"].isNullOrBlank()) {
                        status = QrStatus.Failed("登录确认但 Cookie 不完整，请重试")
                        return@LaunchedEffect
                    }
                    AppGraph.store.saveNeteaseCookies(cookies)
                    NeteaseApi.syncCookies()
                    runCatching { NeteaseApi.refreshAccount() }
                    status = QrStatus.Success(
                        nickname = AppGraph.store.neteaseName,
                    )
                    delay(400)
                    onDone()
                    return@LaunchedEffect
                }
                802 -> status = QrStatus.Scanned
                801 -> status = QrStatus.Waiting
                800 -> {
                    status = QrStatus.Failed("二维码已过期，请刷新")
                    return@LaunchedEffect
                }
                else -> {
                    status = QrStatus.Failed(check.message.ifBlank { "code=${check.code}" })
                    return@LaunchedEffect
                }
            }
            delay(1500)
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
        Text("使用网易云音乐 App 扫描二维码", style = MaterialTheme.typography.titleMedium)
        Text(
            "登录后可播放会员歌曲、获取账号曲库",
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
                        "二维码生成失败",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                else -> androidx.compose.foundation.Image(
                    bitmap = qrBitmap!!.asImageBitmap(),
                    contentDescription = "登录二维码",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
        }

        val text = when (val s = status) {
            is QrStatus.Waiting -> "请打开网易云音乐 App 扫码"
            is QrStatus.Scanned -> "已扫描，请在手机上确认登录"
            is QrStatus.Failed -> s.reason
            is QrStatus.Success -> "登录成功"
        }
        Text(
            text,
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
                Text("刷新二维码")
            }
        }
    }
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
