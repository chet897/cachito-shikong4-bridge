package dev.shikongbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 本地优先滑块：relay 推回来的 state 和本机已播的一样就别重播。 */
class BridgeLinkTest {

    @Before
    fun reset() {
        BridgeLink.resetLocalEcho()
    }

    @Test
    fun unknownEchoAlwaysApplies() {
        assertTrue(BridgeLink.shouldApplyFromRelay(0, 0))
        assertTrue(BridgeLink.shouldApplyFromRelay(40, 30))
    }

    @Test
    fun sameValueIsIgnored() {
        BridgeLink.markFromRelay(40, 30)
        assertFalse(BridgeLink.shouldApplyFromRelay(40, 30))
    }

    @Test
    fun differentValueWins() {
        BridgeLink.markFromRelay(40, 30)
        // AI 那边改了档，以 relay 为准
        assertTrue(BridgeLink.shouldApplyFromRelay(60, 30))
        assertTrue(BridgeLink.shouldApplyFromRelay(40, 0))
    }

    @Test
    fun applyLocalWithoutServiceReportsFalse() {
        BridgeLink.engine = null
        assertFalse(BridgeLink.applyLocal(40, 0))
    }
}
