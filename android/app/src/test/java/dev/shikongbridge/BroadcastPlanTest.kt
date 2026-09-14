package dev.shikongbridge

import dev.shikongbridge.BroadcastPlan.Step
import dev.shikongbridge.ShikongProtocol.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广播排期。重点钉死全停序列的顺序 ——
 * 2026-09-14 真机实测：「总关 02 00」只关得住入体端，吮吸端必须单独发「吮吸关 01 00」。
 */
class BroadcastPlanTest {

    /** 全停 = 先吮吸关 1.2 秒，再总关 1.5 秒，然后停播。顺序不能反。 */
    @Test
    fun stopAllIsSuckOffThenAllOff() {
        val plan = BroadcastPlan.stopAll()
        assertEquals(
            listOf(
                Step(Cmd.SuckOff, 1200L),
                Step(Cmd.AllOff, 1500L),
            ),
            plan.prelude
        )
        assertEquals(emptyList<Cmd>(), plan.cycle)
    }

    /** 序列里第一条必须是吮吸关，不能只发总关。 */
    @Test
    fun stopAllNeverSendsOnlyAllOff() {
        val cmds = BroadcastPlan.stopAll().prelude.map { it.cmd }
        assertEquals(Cmd.SuckOff, cmds.first())
        assertTrue(cmds.contains(Cmd.AllOff))
        assertTrue(cmds.indexOf(Cmd.SuckOff) < cmds.indexOf(Cmd.AllOff))
    }

    /** 收到 state 两通道都 0，要走同一套全停序列。 */
    @Test
    fun bothToZeroUsesStopAllSequence() {
        assertEquals(
            BroadcastPlan.stopAll(),
            BroadcastPlan.forState(40, 30, 0, 0, everAdvertised = true)
        )
        assertEquals(
            BroadcastPlan.stopAll(),
            BroadcastPlan.forState(40, 0, 0, 0, everAdvertised = true)
        )
        assertEquals(
            BroadcastPlan.stopAll(),
            BroadcastPlan.forState(0, 30, 0, 0, everAdvertised = true)
        )
    }

    /** 本来就什么都没播，两个 0 不用白播一轮。 */
    @Test
    fun zeroToZeroWithNothingPlayingIsIdle() {
        assertEquals(
            BroadcastPlan.idle,
            BroadcastPlan.forState(0, 0, 0, 0, everAdvertised = false)
        )
    }

    /** suck 单独归零：吮吸关 1.2 秒，然后只剩入体在播。 */
    @Test
    fun suckToZeroKeepsInvibRunning() {
        val plan = BroadcastPlan.forState(40, 30, 0, 30, everAdvertised = true)
        assertEquals(listOf(Step(Cmd.SuckOff, 1200L)), plan.prelude)
        assertEquals(listOf<Cmd>(Cmd.Invib(30)), plan.cycle)
    }

    /** invib 单独归零：入体没有单独关命令，用总关 1.2 秒，之后 suck 由稳态循环恢复。 */
    @Test
    fun invibToZeroUsesAllOffThenRestoresSuck() {
        val plan = BroadcastPlan.forState(40, 30, 40, 0, everAdvertised = true)
        assertEquals(listOf(Step(Cmd.AllOff, 1200L)), plan.prelude)
        assertEquals(listOf<Cmd>(Cmd.Suck(40)), plan.cycle)
    }

    /** 两条都非零：不用过渡，suck 先、invib 后轮播。 */
    @Test
    fun bothNonZeroRotates() {
        val plan = BroadcastPlan.forState(0, 0, 40, 30, everAdvertised = false)
        assertEquals(emptyList<Step>(), plan.prelude)
        assertEquals(listOf<Cmd>(Cmd.Suck(40), Cmd.Invib(30)), plan.cycle)
        assertEquals(400L, BroadcastPlan.ROTATE_MS)
    }

    /** 只有一条非零就一直播那条。 */
    @Test
    fun singleChannelHasOneEntry() {
        assertEquals(
            listOf<Cmd>(Cmd.Suck(55)),
            BroadcastPlan.forState(0, 0, 55, 0, everAdvertised = false).cycle
        )
        assertEquals(
            listOf<Cmd>(Cmd.Invib(55)),
            BroadcastPlan.forState(0, 0, 0, 55, everAdvertised = false).cycle
        )
    }

    /** 全停序列实际发出去的 UUID：第一条 01 00（吮吸关），第二条 02 00（总关）。 */
    @Test
    fun stopAllEmitsSuckOffThenAllOffUuids() {
        val uuids = BroadcastPlan.stopAll().prelude.map { ShikongProtocol.build(it.cmd) }
        assertEquals(2, uuids.size)
        // 类型字段是第 5-6 字节，UUID 字符串第二段
        assertEquals("0100", uuids[0].split("-")[1])
        assertEquals("0200", uuids[1].split("-")[1])
    }
}
