package top.aryun.yzliyin.listentogether

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.aryun.yzliyin.listentogether.invite.ListenTogetherInvite

/**
 * 深链邀请总线：MainActivity 解析出邀请后投递，App 根组件弹确认框消费。
 *
 * 单独放总线是因为深链可能在任意页面到达（singleTask + onNewIntent），
 * 而确认弹窗需要在 Compose 根布局渲染。
 */
object ListenTogetherInviteBus {

    private val _pending = MutableStateFlow<ListenTogetherInvite?>(null)
    val pending: StateFlow<ListenTogetherInvite?> = _pending.asStateFlow()

    fun publish(invite: ListenTogetherInvite) {
        _pending.value = invite
    }

    fun consume() {
        _pending.value = null
    }
}
