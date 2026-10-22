package top.aryun.yzliyin.core.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.brotli.dec.BrotliInputStream
import org.json.JSONArray
import org.json.JSONObject
import okio.ByteString
import top.aryun.yzliyin.util.NPLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

/**
 * 直播弹幕 websocket 客户端。
 *
 * 包头 16 字节（大端）：`totalLen(u32) | headerLen(u16=16) | protoVer(u16) | op(u32) | seq(u32)`。
 * 连上后先发 op7 认证包等 op8 回包，之后每 30 秒发一个 op2 空包保活（服务端踢掉不发包的连接）；
 * op5 是消息推送，`protoVer` 决定要不要解压（2 = zlib、3 = brotli）——
 * **解出来还是一整串包**，得按 `totalLen` 再切一轮才拿得到真正的 JSON。
 *
 * 认证带 `getDanmuInfo` 换来的 token；拿不到时传空串也能匿名读，但地址至少要有一个。
 * 断线会换下一个地址自动重连，连试几次都失败才放弃。
 */
class BiliLiveDanmaku(
    private val hosts: List<String>,
    private val token: String,
    private val roomId: Long,
    private val uid: Long,
    private val onDanmaku: (BiliLiveMsg) -> Unit,
    private val onState: (String) -> Unit = {},
) {

    private companion object {
        const val TAG = "BiliLiveDanmaku"
        const val HEADER_SIZE = 16

        const val OP_HEARTBEAT = 2
        const val OP_MESSAGE = 5
        const val OP_AUTH = 7
        const val OP_AUTH_OK = 8

        const val HEARTBEAT_MS = 30_000L
        const val MAX_ATTEMPTS = 5
        /** 上一次连接稳稳撑过这么久，就当链路本身是好的，失败计数重新开始。 */
        const val HEALTHY_MS = 60_000L
        /** 解压后再切包最多下钻两层，防异常数据绕成死循环。 */
        const val MAX_NEST_DEPTH = 2

        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接，读超时交给服务端判断
        .build()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var hostIndex = 0
    private var attempts = 0

    /** 最近一次连上的时刻；用于把「接上就断」和「稳稳跑过」区分开。 */
    private var connectedAtMs = 0L

    @Volatile
    private var closed = false

    fun start() {
        if (closed) return
        if (hosts.isEmpty()) {
            onState("no_host")
            return
        }
        connect()
    }

    /** 手动断开（退出房间页时调用），之后不再重连。 */
    fun close() {
        closed = true
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        val socket = webSocket
        webSocket = null
        socket?.close(1000, "left room")
        scope.cancel()
    }

    private fun connect() {
        val host = hosts[hostIndex % hosts.size]
        val request = Request.Builder()
            .url(host)
            .header("User-Agent", UA)
            .header("Referer", "https://live.bilibili.com/$roomId")
            .build()
        NPLogger.d(TAG, "连接弹幕服务器：$host")

        webSocket = http.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (closed) {
                        webSocket.close(1000, "closed")
                        return
                    }
                    connectedAtMs = System.currentTimeMillis()
                    webSocket.send(authPacket())
                    onState("connected")
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (closed) return
                    runCatching { dispatch(bytes.toByteArray()) }
                        .onFailure { NPLogger.w(TAG, "解析弹幕包失败：${it.message}") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (closed) return
                    NPLogger.w(TAG, "弹幕连接失败：${t.message}")
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (closed) return
                    scheduleReconnect()
                }
            },
        )
    }

    private fun scheduleReconnect() {
        heartbeatJob?.cancel()
        if (closed) return
        // 连上秒断不算「好了」：只有上次真的撑住了，才把计数清零。
        // 否则服务端接上就把我们踢掉时，这里会无限重连下去。
        if (connectedAtMs > 0 && System.currentTimeMillis() - connectedAtMs >= HEALTHY_MS) {
            attempts = 0
        }
        connectedAtMs = 0
        attempts++
        if (attempts >= MAX_ATTEMPTS) {
            onState("failed")
            return
        }
        hostIndex++
        reconnectJob = scope.launch {
            delay(1_500L * attempts)
            if (!closed) connect()
        }
    }

    /** 认证包：请求 protover=3（brotli），匿名时 uid=0、key 传空也能读弹幕。 */
    private fun authPacket(): ByteString = packet(
        OP_AUTH,
        JSONObject()
            .put("roomid", roomId)
            .put("uid", uid)
            .put("protover", 3)
            .put("platform", "web")
            .put("type", 2)
            .put("key", token)
            .toString()
            .toByteArray(Charsets.UTF_8),
    )

    /** 组一个 16 字节头 + 正文体的包，认证与保活都靠它。 */
    private fun packet(op: Int, body: ByteArray = ByteArray(0)): ByteString =
        ByteString.of(
            *ByteBuffer.allocate(HEADER_SIZE + body.size)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(HEADER_SIZE + body.size)
                .putShort(HEADER_SIZE.toShort())
                .putShort(1) // 认证/心跳包的 protoVer 恒为 1
                .putInt(op)
                .putInt(1)
                .put(body)
                .array(),
        )

    /** 收到字节流先按 `totalLen` 切成若干包，再逐个按 op 分发。 */
    private fun dispatch(bytes: ByteArray) {
        for (p in splitPackets(bytes)) when (p.op) {
            OP_AUTH_OK -> startHeartbeat()
            OP_MESSAGE -> handleMessage(p, 0)
            else -> Unit // op3 是心跳回包（人气值），不用处理
        }
    }

    private fun handleMessage(p: Packet, depth: Int) {
        if (depth > MAX_NEST_DEPTH) return
        val body = when (p.ver) {
            2 -> inflate(p.body, raw = true) ?: inflate(p.body, raw = false) ?: return
            3 -> brotli(p.body) ?: return
            else -> p.body
        }
        // totalLen 的最高字节为 0 → 体里还套着整串包（压缩层解开后的常见形态）
        val isNested = body.size >= HEADER_SIZE && body[0].toInt() == 0
        if (isNested) {
            for (inner in splitPackets(body)) {
                if (inner.op == OP_MESSAGE) handleMessage(inner, depth + 1)
            }
        } else {
            parsePayload(body)
        }
    }

    private fun parsePayload(body: ByteArray) {
        val json = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return
        val cmd = json.optString("cmd")
        when {
            cmd.startsWith("DANMU_MSG") -> parseDanmaku(json)?.let(onDanmaku)

            // 醒目留言（付费弹幕）：当成一条高亮消息一起推给界面
            cmd == "SUPER_CHAT_MESSAGE" -> {
                val data = json.optJSONObject("data") ?: return
                val text = data.optString("message")
                if (text.isNotBlank()) {
                    val user = data.optJSONObject("user")
                    onDanmaku(
                        BiliLiveMsg(
                            text = text,
                            userName = user?.optString("uname").orEmpty(),
                            uid = user?.optLong("uid") ?: 0L,
                            color = 0xFFFFD4A0L.toInt(),
                            mode = 5,
                        ),
                    )
                }
            }

            // 主播下播：让房间页知道没得看了，而不是一直转圈
            cmd == "PREPARING" -> onState("preparing")
        }
    }

    private fun parseDanmaku(json: JSONObject): BiliLiveMsg? {
        val info = json.optJSONArray("info") ?: return null
        val text = info.optString(1)
        if (text.isBlank()) return null

        var name = ""
        var uid = 0L
        var rgb = 0xFFFFFF
        var mode = 1

        // info[0] = [模式, 字号, 颜色, 时间戳, ...]
        (info.opt(0) as? JSONArray)?.let { head ->
            mode = head.optInt(0, 1)
            rgb = head.optInt(2, 0xFFFFFF)
        }
        // info[2] = [uid, 昵称, ...]（旧格式）
        (info.opt(2) as? JSONArray)?.let { ident ->
            uid = ident.optLong(0)
            name = ident.optString(1)
        }
        // info[15] = 新格式：user/base/medal + extra 里才是真实颜色与模式
        (info.opt(15) as? JSONObject)?.let { ext ->
            val user = ext.optJSONObject("user")
            user?.optJSONObject("base")?.optString("name")?.takeIf { it.isNotBlank() }?.let {
                name = it
            }
            val u = user?.optLong("uid") ?: 0L
            if (u > 0) uid = u
            runCatching { JSONObject(ext.optString("extra")) }.getOrNull()?.let { extra ->
                if (extra.has("color")) rgb = extra.optInt("color", rgb)
                if (extra.has("mode")) mode = extra.optInt("mode", mode)
            }
        }
        if (name.isBlank()) return null

        return BiliLiveMsg(
            text = text,
            userName = name,
            uid = uid,
            color = (0xFF000000L or (rgb.toLong() and 0xFFFFFF)).toInt(),
            mode = mode,
        )
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                val socket = webSocket ?: break
                if (!socket.send(packet(OP_HEARTBEAT))) break
            }
        }
    }

    private fun splitPackets(bytes: ByteArray): List<Packet> {
        val out = mutableListOf<Packet>()
        var offset = 0
        while (offset + HEADER_SIZE <= bytes.size) {
            val header = ByteBuffer.wrap(bytes, offset, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            val total = header.int
            val ver = header.short.toInt() and 0xFFFF
            val op = header.int
            // 长度不合理就整批丢掉，绝不越界读
            if (total < HEADER_SIZE || offset + total > bytes.size) break
            out += Packet(ver, op, bytes.copyOfRange(offset + HEADER_SIZE, offset + total))
            offset += total
        }
        return out
    }

    /** zlib 解压；帧分"带 zlib 头"与"裸 deflate"两种派别，两种都试一次。 */
    private fun inflate(bytes: ByteArray, raw: Boolean): ByteArray? = runCatching {
        val inflater = Inflater(raw)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        inflater.setInput(bytes)
        while (true) {
            val n = inflater.inflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        inflater.end()
        out.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun brotli(bytes: ByteArray): ByteArray? = runCatching {
        BrotliInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    private class Packet(val ver: Int, val op: Int, val body: ByteArray)
}
