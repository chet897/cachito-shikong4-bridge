package dev.shikongbridge

import org.json.JSONObject

/**
 * `GET /api/state` 的返回（接口约定第 3 节）。
 *
 * WebSocket 推的 state 里**没有** pattern 字段（只有 suck/invib 两个档位），
 * 所以「现在在跑哪个模式」得靠控制页每 2 秒拉一次这个接口。
 */
data class RelaySnapshot(
    val suck: Int,
    val invib: Int,
    val suckPattern: String?,
    val invibPattern: String?,
    val phoneOnline: Boolean,
) {
    companion object {
        fun parse(json: String): RelaySnapshot? {
            val o = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            val s = o.optJSONObject("suck")
            val v = o.optJSONObject("invib")
            return RelaySnapshot(
                suck = s?.optInt("level", 0) ?: 0,
                invib = v?.optInt("level", 0) ?: 0,
                suckPattern = s?.optString("pattern")?.takeIf { it.isNotBlank() && it != "null" },
                invibPattern = v?.optString("pattern")?.takeIf { it.isNotBlank() && it != "null" },
                phoneOnline = o.optBoolean("phone_online", false),
            )
        }
    }
}
