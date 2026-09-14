package dev.shikongbridge

import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * 失控 4.0 广播命令编码器（接口约定第 7 节）。
 *
 * 一条命令 = 16 字节 = 一个 128 位 UUID（字符串按字节从左到右排）：
 * [0]71 [1]00 [2]17 [3]nonce [4-5]类型 [6]9E [7]D5 [8-9]01 00 [10-14]载荷 [15]校验和
 * 校验和 = 前 15 字节之和 mod 256；nonce 每条随机 0-255。
 *
 * 纯 JVM 代码，不依赖 Android，方便单元测试。
 */
object ShikongProtocol {

    /** 一条待广播的命令。 */
    sealed class Cmd {
        /** 吮吸端开到 level（1-100）。 */
        data class Suck(val level: Int) : Cmd()

        /** 入体端开到 level（1-100）。 */
        data class Invib(val level: Int) : Cmd()

        /** 吮吸关。 */
        data object SuckOff : Cmd()

        /** 总关（两端全停）。 */
        data object AllOff : Cmd()
    }

    fun checksum(bytes15: List<Int>): Int {
        require(bytes15.size == 15) { "checksum_requires_15_bytes" }
        return bytes15.sumOf { it and 0xff } and 0xff
    }

    private fun frame(nonce: Int, type: List<Int>, payload: List<Int>): String {
        require(nonce in 0..255) { "invalid_nonce" }
        require(type.size == 2) { "invalid_type" }
        require(payload.size == 5) { "invalid_payload" }
        val b15 = listOf(0x71, 0x00, 0x17, nonce) +
            type +
            listOf(0x9E, 0xD5, 0x01, 0x00) +
            payload
        return hexUuid(b15 + checksum(b15))
    }

    private fun hexUuid(bytes16: List<Int>): String {
        require(bytes16.size == 16) { "uuid_requires_16_bytes" }
        val hex = bytes16.joinToString("") { String.format(Locale.US, "%02X", it and 0xff) }
        return listOf(
            hex.substring(0, 8),
            hex.substring(8, 12),
            hex.substring(12, 16),
            hex.substring(16, 20),
            hex.substring(20, 32),
        ).joinToString("-")
    }

    fun randomNonce(): Int = Random.nextInt(0, 256)

    /** 吮吸：类型 51 00，载荷 64 00 00 [50 + level/2] 02 */
    fun suck(level: Int, nonce: Int = randomNonce()): String {
        require(level in 1..100) { "invalid_level" }
        return frame(nonce, listOf(0x51, 0x00), listOf(0x64, 0x00, 0x00, 50 + level / 2, 0x02))
    }

    /** 吮吸关：类型 01 00，载荷 64 00 00 00 02 */
    fun suckOff(nonce: Int = randomNonce()): String =
        frame(nonce, listOf(0x01, 0x00), listOf(0x64, 0x00, 0x00, 0x00, 0x02))

    /** 入体震动：类型 52 00，载荷 2D [102 - level] 15 00 02（越小越猛） */
    fun invib(level: Int, nonce: Int = randomNonce()): String {
        require(level in 1..100) { "invalid_level" }
        return frame(nonce, listOf(0x52, 0x00), listOf(0x2D, 102 - level, 0x15, 0x00, 0x02))
    }

    /** 总关：类型 02 00，载荷全 0 */
    fun allOff(nonce: Int = randomNonce()): String =
        frame(nonce, listOf(0x02, 0x00), listOf(0x00, 0x00, 0x00, 0x00, 0x00))

    /** 按命令生成一条新 UUID（每次都换 nonce）。 */
    fun build(cmd: Cmd): String = when (cmd) {
        is Cmd.Suck -> suck(cmd.level)
        is Cmd.Invib -> invib(cmd.level)
        Cmd.SuckOff -> suckOff()
        Cmd.AllOff -> allOff()
    }

    fun asUuid(text: String): UUID = UUID.fromString(text)
}
