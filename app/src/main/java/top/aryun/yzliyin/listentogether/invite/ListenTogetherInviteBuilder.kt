package top.aryun.yzliyin.listentogether.invite

import android.net.Uri
import top.aryun.yzliyin.R
import top.aryun.yzliyin.listentogether.network.http.normalizeBaseUrl
import top.aryun.yzliyin.listentogether.validation.requireValidListenTogetherJoinSecret
import top.aryun.yzliyin.listentogether.validation.requireValidListenTogetherNickname
import top.aryun.yzliyin.listentogether.validation.requireValidListenTogetherRoomId
import top.aryun.yzliyin.util.AppLocale
import java.util.UUID

private const val LISTEN_TOGETHER_INVITE_SCHEME = "neriplayer"
private const val LISTEN_TOGETHER_INVITE_HOST = "listen-together"
internal const val LISTEN_TOGETHER_INVITE_JOIN_PATH = "join"

fun buildListenTogetherUserUuid(): String {
    return UUID.randomUUID().toString()
}

fun buildDefaultListenTogetherNickname(): String {
    return AppLocale.string(
        R.string.lt_invite_default_nickname,
        UUID.randomUUID().toString().replace("-", "").take(4).uppercase(),
    )
}

fun buildListenTogetherInviteUri(
    roomId: String,
    inviterNickname: String? = null,
    baseUrl: String? = null,
    joinSecret: String
): String {
    val normalizedRoomId = requireValidListenTogetherRoomId(roomId)
    val normalizedJoinSecret = requireValidListenTogetherJoinSecret(joinSecret)
    val normalizedBaseUrl = baseUrl
        ?.takeIf { it.isNotBlank() }
        ?.normalizeBaseUrl()
        ?.takeUnless { isDefaultListenTogetherBaseUrl(it) }
    return Uri.Builder()
        .scheme(LISTEN_TOGETHER_INVITE_SCHEME)
        .authority(LISTEN_TOGETHER_INVITE_HOST)
        .appendPath(LISTEN_TOGETHER_INVITE_JOIN_PATH)
        .appendQueryParameter("roomId", normalizedRoomId)
        .apply {
            inviterNickname?.takeIf { it.isNotBlank() }?.let {
                appendQueryParameter("inviter", requireValidListenTogetherNickname(it))
            }
            normalizedBaseUrl?.let {
                appendQueryParameter("baseUrl", it)
            }
            appendQueryParameter("secret", normalizedJoinSecret)
        }
        .build()
        .toString()
}
