package top.aryun.yzliyin.listentogether.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.aryun.yzliyin.listentogether.invite.buildDefaultListenTogetherNickname
import top.aryun.yzliyin.listentogether.invite.buildListenTogetherUserUuid
import top.aryun.yzliyin.listentogether.invite.configuredListenTogetherBaseUrlOrNull
import top.aryun.yzliyin.listentogether.invite.isDefaultListenTogetherBaseUrl
import top.aryun.yzliyin.listentogether.validation.sanitizeListenTogetherNicknameOrNull

/**
 * 一起听的本地偏好（SharedPreferences + StateFlow）。
 *
 * 字段与上游 ListenTogetherPreferences 一致：
 *  - workerBaseUrl：自定义服务端地址；使用默认服务器时存空串
 *  - workerBaseUrlInput：输入框原文（可能带端口/路径），空时回落到 workerBaseUrl
 *  - userUuid / nickname：本机身份，进房后成员列表以此区分
 *  - allowMemberControl / autoPauseOnMemberChange / shareAudioLinks：房主建房默认房间设置
 */
class ListenTogetherPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("yzliyin_listen_together", Context.MODE_PRIVATE)

    private val _workerBaseUrl = MutableStateFlow(readCustomServer())
    val workerBaseUrl: StateFlow<String> = _workerBaseUrl.asStateFlow()

    private val _workerBaseUrlInput = MutableStateFlow(readServerInput())
    val workerBaseUrlInput: StateFlow<String> = _workerBaseUrlInput.asStateFlow()

    private val _userUuid = MutableStateFlow(prefs.getString(KEY_USER_UUID, "").orEmpty().trim())
    val userUuid: StateFlow<String> = _userUuid.asStateFlow()

    private val _nickname = MutableStateFlow(readNickname())
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    private val _allowMemberControl = MutableStateFlow(prefs.getBoolean(KEY_ALLOW_MEMBER_CONTROL, true))
    val allowMemberControl: StateFlow<Boolean> = _allowMemberControl.asStateFlow()

    private val _autoPauseOnMemberChange =
        MutableStateFlow(prefs.getBoolean(KEY_AUTO_PAUSE_ON_MEMBER_CHANGE, true))
    val autoPauseOnMemberChange: StateFlow<Boolean> = _autoPauseOnMemberChange.asStateFlow()

    private val _shareAudioLinks = MutableStateFlow(prefs.getBoolean(KEY_SHARE_AUDIO_LINKS, true))
    val shareAudioLinks: StateFlow<Boolean> = _shareAudioLinks.asStateFlow()

    private fun readCustomServer(): String {
        val raw = prefs.getString(KEY_WORKER_BASE_URL, "").orEmpty()
        return configuredListenTogetherBaseUrlOrNull(raw)
            ?.takeUnless { isDefaultListenTogetherBaseUrl(it) }
            .orEmpty()
    }

    private fun readServerInput(): String {
        val savedInput = prefs.getString(KEY_WORKER_BASE_URL_INPUT, "").orEmpty().trim()
        return savedInput.ifBlank { readCustomServer() }
    }

    private fun readNickname(): String {
        return sanitizeListenTogetherNicknameOrNull(prefs.getString(KEY_NICKNAME, ""))
            .orEmpty()
    }

    fun setWorkerBaseUrl(value: String) {
        val normalized = configuredListenTogetherBaseUrlOrNull(value).orEmpty()
        val stored = normalized.takeUnless { isDefaultListenTogetherBaseUrl(it) }.orEmpty()
        prefs.edit {
            if (stored.isBlank()) remove(KEY_WORKER_BASE_URL) else putString(KEY_WORKER_BASE_URL, stored)
        }
        _workerBaseUrl.value = stored
    }

    /** 保存输入框原文（服务端地址可能带端口/路径，校验通过即可用）。 */
    fun setWorkerBaseUrlInput(value: String) {
        val trimmed = value.trim()
        val normalized = configuredListenTogetherBaseUrlOrNull(trimmed)
        val stored = if (trimmed.isBlank() || (normalized != null && isDefaultListenTogetherBaseUrl(normalized))) {
            ""
        } else {
            trimmed
        }
        prefs.edit {
            if (stored.isBlank()) remove(KEY_WORKER_BASE_URL_INPUT) else putString(KEY_WORKER_BASE_URL_INPUT, stored)
        }
        _workerBaseUrlInput.value = stored.ifBlank { _workerBaseUrl.value }
    }

    fun setNickname(value: String) {
        val normalized = value.trim()
        prefs.edit {
            if (normalized.isBlank()) remove(KEY_NICKNAME) else putString(KEY_NICKNAME, normalized)
        }
        _nickname.value = sanitizeListenTogetherNicknameOrNull(normalized).orEmpty()
    }

    fun setUserUuid(value: String) {
        val normalized = value.trim()
        prefs.edit {
            if (normalized.isBlank()) remove(KEY_USER_UUID) else putString(KEY_USER_UUID, normalized)
        }
        _userUuid.value = normalized
    }

    /** 取本机身份 UUID，首次使用时生成并保存。 */
    fun getOrCreateUserUuid(): String {
        val existing = _userUuid.value
        if (existing.isNotBlank()) return existing
        val created = buildListenTogetherUserUuid()
        setUserUuid(created)
        return created
    }

    /** 重置身份 UUID（需先离开房间，旧身份即时失效）。 */
    fun resetUserUuid(): String {
        val created = buildListenTogetherUserUuid()
        setUserUuid(created)
        return created
    }

    /** 取昵称，未设置时生成一个默认昵称并保存。 */
    fun getOrCreateNickname(): String {
        val existing = sanitizeListenTogetherNicknameOrNull(_nickname.value).orEmpty()
        if (existing.isNotBlank()) return existing
        val created = buildDefaultListenTogetherNickname()
        setNickname(created)
        return created
    }

    fun setAllowMemberControl(value: Boolean) {
        prefs.edit { putBoolean(KEY_ALLOW_MEMBER_CONTROL, value) }
        _allowMemberControl.value = value
    }

    fun setAutoPauseOnMemberChange(value: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_PAUSE_ON_MEMBER_CHANGE, value) }
        _autoPauseOnMemberChange.value = value
    }

    fun setShareAudioLinks(value: Boolean) {
        prefs.edit { putBoolean(KEY_SHARE_AUDIO_LINKS, value) }
        _shareAudioLinks.value = value
    }

    private companion object {
        const val KEY_WORKER_BASE_URL = "listen_together_worker_base_url"
        const val KEY_WORKER_BASE_URL_INPUT = "listen_together_worker_base_url_input"
        const val KEY_USER_UUID = "listen_together_user_uuid"
        const val KEY_NICKNAME = "listen_together_nickname"
        const val KEY_ALLOW_MEMBER_CONTROL = "listen_together_allow_member_control"
        const val KEY_AUTO_PAUSE_ON_MEMBER_CHANGE = "listen_together_auto_pause_on_member_change"
        const val KEY_SHARE_AUDIO_LINKS = "listen_together_share_audio_links"
    }
}
