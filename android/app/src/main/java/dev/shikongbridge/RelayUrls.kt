package dev.shikongbridge

/**
 * WebSocket 地址 ⇄ HTTP 基址的换算。纯函数，方便单元测试。
 *
 * wss://YOUR_DOMAIN/shikong/phone/ws  ->  https://YOUR_DOMAIN/shikong
 * ws://YOUR_VPS_IP:8895/phone/ws     ->  http://YOUR_VPS_IP:8895
 */
object RelayUrls {

    fun httpBase(wsUrl: String): String {
        var u = wsUrl.trim()
        u = when {
            u.startsWith("wss://", ignoreCase = true) -> "https://" + u.substring(6)
            u.startsWith("ws://", ignoreCase = true) -> "http://" + u.substring(5)
            else -> u
        }
        u = u.substringBefore('?').substringBefore('#').trimEnd('/')
        if (u.endsWith("/phone/ws", ignoreCase = true)) {
            u = u.dropLast("/phone/ws".length)
        }
        return u.trimEnd('/')
    }

    fun api(wsUrl: String, path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return httpBase(wsUrl) + p
    }

    /** 给 WebSocket 地址补上 ?token=xxx。 */
    fun wsWithToken(wsUrl: String, token: String): String {
        val base = wsUrl.trim().trimEnd('/')
        val sep = if (base.contains('?')) "&" else "?"
        return base + sep + "token=" + java.net.URLEncoder.encode(token, "UTF-8")
    }
}
