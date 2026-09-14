package dev.shikongbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 接口约定第 7 节的 6 条自检样本，同 nonce 必须逐字节一致。
 */
class ShikongProtocolTest {

    @Test
    fun suck25() {
        assertEquals("71001763-5100-9ED5-0100-6400003E0254", ShikongProtocol.suck(25, 0x63))
    }

    @Test
    fun suck100() {
        assertEquals("7100176F-5100-9ED5-0100-640000640286", ShikongProtocol.suck(100, 0x6F))
    }

    @Test
    fun suckOff() {
        assertEquals("7100171F-0100-9ED5-0100-640000000282", ShikongProtocol.suckOff(0x1F))
    }

    @Test
    fun invib50() {
        assertEquals("71001766-5200-9ED5-0100-2D341500022C", ShikongProtocol.invib(50, 0x66))
    }

    @Test
    fun invib100() {
        assertEquals("7100173F-5200-9ED5-0100-2D02150002D3", ShikongProtocol.invib(100, 0x3F))
    }

    @Test
    fun allOff() {
        assertEquals("71001777-0200-9ED5-0100-000000000075", ShikongProtocol.allOff(0x77))
    }

    @Test
    fun uuidParsableByJava() {
        // 生成的字符串必须能被 UUID.fromString 吃下（广播层要用）
        for (level in 1..100) {
            ShikongProtocol.asUuid(ShikongProtocol.suck(level))
            ShikongProtocol.asUuid(ShikongProtocol.invib(level))
        }
        ShikongProtocol.asUuid(ShikongProtocol.suckOff())
        ShikongProtocol.asUuid(ShikongProtocol.allOff())
    }

    @Test
    fun checksumIsSumMod256() {
        assertEquals(0x54, ShikongProtocol.checksum(
            listOf(0x71, 0x00, 0x17, 0x63, 0x51, 0x00, 0x9E, 0xD5, 0x01, 0x00, 0x64, 0x00, 0x00, 0x3E, 0x02)
        ))
    }

    @Test
    fun levelOutOfRangeRejected() {
        assertThrows(IllegalArgumentException::class.java) { ShikongProtocol.suck(0) }
        assertThrows(IllegalArgumentException::class.java) { ShikongProtocol.invib(101) }
    }
}
