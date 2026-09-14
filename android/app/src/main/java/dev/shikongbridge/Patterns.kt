package dev.shikongbridge

import org.json.JSONArray

/**
 * `GET /api/patterns` 返回的一条模式（接口约定第 6 节 v2 格式）。
 *
 * 例：`{"name":"暗涌","desc":"…","official":true,
 *      "suck":[[40,1.5]],"suck_text":"40档×1.5秒 → …","suck_cycle_s":5.5, "invib_…"}`
 * App 只用得上给人看的字段（名字、说明、两端的人话序列和一圈几秒），
 * 具体档位序列由 relay 自己跑，手机端不需要。
 */
data class Pattern(
    val name: String,
    val desc: String,
    val suckText: String,
    val invibText: String,
    val suckCycleS: Double,
    val invibCycleS: Double,
)

/** 模式列表的 JSON 解析。纯函数，方便单元测试。 */
object Patterns {

    /** 解析 `/api/patterns` 的 JSON 数组。坏数据跳过，不抛异常。 */
    fun parse(json: String): List<Pattern> {
        val arr = try {
            JSONArray(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<Pattern>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            out += Pattern(
                name = name,
                desc = o.optString("desc", "").trim(),
                suckText = o.optString("suck_text", "").trim(),
                invibText = o.optString("invib_text", "").trim(),
                suckCycleS = o.optDouble("suck_cycle_s", 0.0).let { if (it.isNaN()) 0.0 else it },
                invibCycleS = o.optDouble("invib_cycle_s", 0.0).let { if (it.isNaN()) 0.0 else it },
            )
        }
        return out
    }
}
