package dev.shikongbridge

import android.content.Context

/** 设置项（服务器地址、口令、可连接广播开关）。存 SharedPreferences。 */
class Settings(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("shikong_bridge", Context.MODE_PRIVATE)

    var wsUrl: String
        get() = sp.getString(KEY_URL, DEFAULT_WS_URL)!!.ifBlank { DEFAULT_WS_URL }
        set(value) = sp.edit().putString(KEY_URL, value.trim()).apply()

    /** 口令。**不内置**，必须用户自己输入一次，存在本机。没设置时是空串。 */
    var token: String
        get() = (sp.getString(KEY_TOKEN, "") ?: "").trim()
        set(value) = sp.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** 口令设过没有。没设就不许启动后台服务。 */
    val hasToken: Boolean
        get() = token.isNotBlank()

    /** 可连接广播：Esther 实测失控/大秀要可连接广播才认；某些手机不行时可以关掉。 */
    var connectable: Boolean
        get() = sp.getBoolean(KEY_CONNECTABLE, true)
        set(value) = sp.edit().putBoolean(KEY_CONNECTABLE, value).apply()

    companion object {
        const val APP_VERSION = "0.1.4"
        /**
         * 默认走备用直连入口：Cloudflare 隧道是云端托管配置，那条 /shikong path 规则加不上，
         * 所以先直连 VPS 的 8895（ufw 已放行）。隧道通了以后改回 TUNNEL_WS_URL。
         */
        const val DEFAULT_WS_URL = "ws://YOUR_VPS_IP:8895/phone/ws"

        /** 隧道入口，通了以后在设置页填这个。 */
        const val TUNNEL_WS_URL = "wss://YOUR_DOMAIN/shikong/phone/ws"

        /**
         * **口令一律不内置，这里永远是空串。**
         *
         * 2026-09-14 教训：用户把下载链接经微信/QQ 传到手机，腾讯的安全扫描把 APK 丢进云端
         * 模拟器跑了一遍，拿内置口令真的连上了我们的 relay，把用户本人顶下线
         * （日志里那台 samsung SM-G9860 来自腾讯云）。APK 会被第三方跑起来，内置密钥等于公开。
         * 改口令请改 VPS 上的 /opt/shikong-bridge/token，然后在 App 里手输一次。
         */
        const val DEFAULT_TOKEN = ""

        private const val KEY_URL = "ws_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_CONNECTABLE = "connectable"
    }
}
