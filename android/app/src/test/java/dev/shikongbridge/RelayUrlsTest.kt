package dev.shikongbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayUrlsTest {

    @Test
    fun tunnelUrl() {
        assertEquals(
            "https://YOUR_DOMAIN/shikong",
            RelayUrls.httpBase("wss://YOUR_DOMAIN/shikong/phone/ws")
        )
        assertEquals(
            "https://YOUR_DOMAIN/shikong/api/set",
            RelayUrls.api("wss://YOUR_DOMAIN/shikong/phone/ws", "/api/set")
        )
    }

    @Test
    fun directIpUrl() {
        assertEquals(
            "http://YOUR_VPS_IP:8895",
            RelayUrls.httpBase("ws://YOUR_VPS_IP:8895/phone/ws")
        )
        assertEquals(
            "http://YOUR_VPS_IP:8895/api/stop",
            RelayUrls.api("ws://YOUR_VPS_IP:8895/phone/ws?token=abc", "/api/stop")
        )
    }

    @Test
    fun tokenAppended() {
        assertEquals(
            "wss://YOUR_DOMAIN/shikong/phone/ws?token=abc",
            RelayUrls.wsWithToken("wss://YOUR_DOMAIN/shikong/phone/ws", "abc")
        )
    }

    @Test
    fun defaultIsDirectIp() {
        // 0.1.1 起默认走直连（Cloudflare 隧道那条 /shikong path 规则加不上）
        assertEquals("ws://YOUR_VPS_IP:8895/phone/ws", Settings.DEFAULT_WS_URL)
        assertEquals("wss://YOUR_DOMAIN/shikong/phone/ws", Settings.TUNNEL_WS_URL)
        assertEquals(
            "http://YOUR_VPS_IP:8895/api/set",
            RelayUrls.api(Settings.DEFAULT_WS_URL, "/api/set")
        )
    }

    /**
     * 口令**永远不准内置进 APK**。
     * 2026-09-14：腾讯安全扫描把包丢云端模拟器跑，拿内置口令连上 relay 把用户顶下线了。
     */
    @Test
    fun tokenIsNeverBakedIntoTheApk() {
        assertEquals("", Settings.DEFAULT_TOKEN)
    }
}
