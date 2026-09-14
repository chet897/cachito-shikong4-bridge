package dev.shikongbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/patterns` 和 `GET /api/state` 的解析。
 *
 * relay 用 aiohttp 的 json_response 吐 JSON，中文是 \uXXXX 转义的，所以样本照原样抄一条转义的。
 */
class PatternsTest {

    /**
     * relay 真实返回的样子：aiohttp 的 json_response 默认 ensure_ascii=True，
     * 中文全是 \uXXXX，所以这里照原样抄，顺带验证转义解得开。
     */
    private val escaped = """
        [{"name": "\u6697\u6d8c", "desc": "\u5b98\u65b9\u539f\u7248 #2\u3002\u9519\u5f00\u6da8\u843d",
          "official": true, "suck": [[40, 1.5], [10, 3]],
          "suck_text": "40\u6863\u00d71.5\u79d2 \u2192 10\u6863\u00d73\u79d2", "suck_cycle_s": 4.5,
          "invib": [[35, 3]], "invib_text": "35\u6863\u00d73\u79d2", "invib_cycle_s": 3.0}]
    """.trimIndent()

    @Test
    fun parsesOfficialPattern() {
        val list = Patterns.parse(escaped)
        assertEquals(1, list.size)
        val p = list[0]
        assertEquals("暗涌", p.name)
        assertEquals("官方原版 #2。错开涨落", p.desc)
        assertEquals("40档×1.5秒 → 10档×3秒", p.suckText)
        assertEquals("35档×3秒", p.invibText)
        assertEquals(4.5, p.suckCycleS, 0.001)
        assertEquals(3.0, p.invibCycleS, 0.001)
    }

    @Test
    fun keepsOrderAndSkipsBrokenEntries() {
        val json = """
            [{"name":"玄妙","desc":"官方原版 #1"},
             {"desc":"没名字的不要"},
             "这一条根本不是对象",
             {"name":"旧梦"}]
        """.trimIndent()
        val list = Patterns.parse(json)
        assertEquals(listOf("玄妙", "旧梦"), list.map { it.name })
        // 缺字段不炸，给空串 / 0
        assertEquals("", list[1].desc)
        assertEquals(0.0, list[1].suckCycleS, 0.001)
    }

    @Test
    fun badJsonGivesEmptyListNotCrash() {
        assertTrue(Patterns.parse("").isEmpty())
        assertTrue(Patterns.parse("{\"not\":\"an array\"}").isEmpty())
    }

    /** /api/state：两个通道各自的档位和模式名，外加手机在线与否。 */
    @Test
    fun parsesState() {
        val snap = RelaySnapshot.parse(
            """{"suck":{"level":40,"pattern":"暗涌"},"invib":{"level":0,"pattern":null},
                "seq":123,"phone_online":true,"phone_last_seen_ms":1789000000000}"""
        )!!
        assertEquals(40, snap.suck)
        assertEquals(0, snap.invib)
        assertEquals("暗涌", snap.suckPattern)
        assertNull(snap.invibPattern)
        assertTrue(snap.phoneOnline)
    }
}
