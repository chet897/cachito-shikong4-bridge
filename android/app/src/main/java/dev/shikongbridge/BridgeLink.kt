package dev.shikongbridge

/**
 * 界面 ⇄ 后台服务里那个广播引擎的直通车。
 *
 * 手动滑块要**本地优先**：先让本机立刻按新档位广播（蓝牙物理极限，几十毫秒），
 * 再异步 POST 给 relay。等 relay 转一圈推回来要 1-2 秒，手感太差。
 * relay 推回的 state 如果和本地已经播的一致就忽略（重播会白白打断广播）；
 * 不一致（比如 AI 那边同时也在改）才以 relay 为准 —— relay 仍然是唯一真身。
 */
object BridgeLink {

    /** 没启动后台服务时是 null。 */
    @Volatile
    var engine: BroadcastEngine? = null

    /** 本地立刻播，不等 relay。返回 false 表示后台服务没起来、没播成。 */
    fun applyLocal(suck: Int, invib: Int): Boolean {
        val e = engine ?: return false
        val s = suck.coerceIn(0, 100)
        val v = invib.coerceIn(0, 100)
        BridgeState.localSuck.value = s
        BridgeState.localInvib.value = v
        e.applyState(s, v)
        return true
    }

    /** relay 推来的 state 要不要真去重播一遍。 */
    fun shouldApplyFromRelay(suck: Int, invib: Int): Boolean =
        suck != BridgeState.localSuck.value || invib != BridgeState.localInvib.value

    fun markFromRelay(suck: Int, invib: Int) {
        BridgeState.localSuck.value = suck
        BridgeState.localInvib.value = invib
    }

    /** 服务重建时调用：把"本地已播的值"置成未知，保证下一条 state 一定会真播。 */
    fun resetLocalEcho() {
        BridgeState.localSuck.value = UNKNOWN
        BridgeState.localInvib.value = UNKNOWN
    }

    const val UNKNOWN = -1
}
