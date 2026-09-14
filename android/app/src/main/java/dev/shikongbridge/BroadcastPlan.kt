package dev.shikongbridge

import dev.shikongbridge.ShikongProtocol.Cmd

/**
 * 广播排期（接口约定第 5 节）。纯 JVM、无 Android 依赖，方便单元测试。
 *
 * 2026-09-14 真机教训：「总关 02 00」**实测只关得住入体端，吮吸端关不掉**。
 * 所以任何"全停 / 两通道都 0"的场景，必须先单独发「吮吸关 01 00」，再发「总关」。
 */
object BroadcastPlan {

    /** 一段广播：播 cmd，持续 durationMs 毫秒。 */
    data class Step(val cmd: Cmd, val durationMs: Long)

    /**
     * @param prelude 先按顺序播完的过渡命令（关命令）
     * @param cycle   过渡播完后的稳态循环；空 = 停止广播
     */
    data class Plan(val prelude: List<Step>, val cycle: List<Cmd>)

    /** 两通道都非零时的轮播间隔。 */
    const val ROTATE_MS = 400L

    /** 单通道关命令要播够的时长。 */
    const val CHANNEL_OFF_MS = 1200L

    /** 总关要播够的时长。 */
    const val ALL_OFF_MS = 1500L

    /**
     * 全停序列：**先「吮吸关」1.2 秒 → 再「总关」1.5 秒 → 停播**。
     * 顺序不能反、也不能只发总关，否则吮吸端停不下来（真机实测）。
     */
    fun stopAll(): Plan = Plan(
        prelude = listOf(
            Step(Cmd.SuckOff, CHANNEL_OFF_MS),
            Step(Cmd.AllOff, ALL_OFF_MS),
        ),
        cycle = emptyList(),
    )

    /** 什么都不用做。 */
    val idle: Plan = Plan(emptyList(), emptyList())

    /**
     * 从旧状态切到新状态该怎么播。
     *
     * - 两个都 0：走 [stopAll]（吮吸关 → 总关）。
     * - suck 非零变 0：先播「吮吸关」1.2 秒，再停该通道。
     * - invib 非零变 0：先播「总关」1.2 秒（入体没有单独关命令），
     *   之后 suck 若仍非零会被稳态循环重新播回来。
     * - 稳态：两条都非零就 suck → invib 轮播；只有一条就一直播那条。
     */
    fun forState(
        oldSuck: Int,
        oldInvib: Int,
        newSuck: Int,
        newInvib: Int,
        everAdvertised: Boolean,
    ): Plan {
        if (newSuck == 0 && newInvib == 0) {
            val needsStop = oldSuck > 0 || oldInvib > 0 || everAdvertised
            return if (needsStop) stopAll() else idle
        }

        val prelude = mutableListOf<Step>()
        if (oldSuck > 0 && newSuck == 0) {
            prelude += Step(Cmd.SuckOff, CHANNEL_OFF_MS)
        }
        if (oldInvib > 0 && newInvib == 0) {
            // 入体单独关命令没抓到，用总关；它会把吮吸也关掉，
            // 但下面的稳态循环会立刻把 suck 重新播回来
            prelude += Step(Cmd.AllOff, CHANNEL_OFF_MS)
        }

        val cycle = buildList {
            if (newSuck > 0) add(Cmd.Suck(newSuck))
            if (newInvib > 0) add(Cmd.Invib(newInvib))
        }
        return Plan(prelude, cycle)
    }
}
