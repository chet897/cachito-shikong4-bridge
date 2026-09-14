package dev.shikongbridge

import dev.shikongbridge.LinkStatus.Lamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页四行「链路状态」的文案映射。
 * 用户原话：「到底哪一步断了没说清：口令对不对、手机连没连上 VPS、蓝牙发没发出去」。
 */
class LinkStatusTest {

    @Test
    fun tokenRowText() {
        assertEquals(LinkStatus.Row(Lamp.GREEN, "已填"), LinkStatus.tokenRow(true))
        assertEquals(LinkStatus.Row(Lamp.RED, "没填，点这里填"), LinkStatus.tokenRow(false))
    }

    @Test
    fun serviceRowText() {
        assertEquals(
            LinkStatus.Row(Lamp.GREEN, "运行中"),
            LinkStatus.serviceRow(running = true, starting = false)
        )
        assertEquals(
            LinkStatus.Row(Lamp.GREY, "启动中…"),
            LinkStatus.serviceRow(running = false, starting = true)
        )
        assertEquals(
            LinkStatus.Row(Lamp.RED, "没启动，点下面按钮启动"),
            LinkStatus.serviceRow(running = false, starting = false)
        )
        // 已经在跑就别再显示"启动中"
        assertEquals(
            LinkStatus.Row(Lamp.GREEN, "运行中"),
            LinkStatus.serviceRow(running = true, starting = true)
        )
    }

    @Test
    fun vpsRowShowsHeartbeatAge() {
        assertEquals(
            LinkStatus.Row(Lamp.GREEN, "已连上（3 秒前收到心跳）"),
            LinkStatus.vpsRow(true, false, false, "", 3L)
        )
        assertEquals(
            LinkStatus.Row(Lamp.GREEN, "已连上（还没收到心跳）"),
            LinkStatus.vpsRow(true, false, false, "", null)
        )
    }

    /** 连不上必须写清是哪种失败，不许只写"未连接"。 */
    @Test
    fun vpsRowShowsConcreteFailure() {
        assertEquals(
            LinkStatus.Row(Lamp.RED, "连不上：HTTP 401 口令不对 / ProtocolException"),
            LinkStatus.vpsRow(false, false, false, "HTTP 401 口令不对 / ProtocolException", null)
        )
        assertEquals(
            LinkStatus.Row(Lamp.RED, "连不上：ConnectException: failed to connect"),
            LinkStatus.vpsRow(false, false, false, "ConnectException: failed to connect", null)
        )
        assertEquals(
            LinkStatus.Row(Lamp.GREY, "连接中…"),
            LinkStatus.vpsRow(false, true, false, "", null)
        )
    }

    @Test
    fun vpsRowKickedBeatsEverythingElse() {
        val row = LinkStatus.vpsRow(false, true, true, "随便什么原因", null)
        assertEquals(Lamp.RED, row.lamp)
        assertTrue(row.text.contains("被另一台手机顶下线了"))
        assertTrue(row.text.contains("请关掉另一台上的失控桥"))
    }

    @Test
    fun bleRowText() {
        assertEquals(
            LinkStatus.Row(Lamp.GREEN, "正在播：吮吸 40 / 入体 0"),
            LinkStatus.bleRow(true, 40, 0, null, "")
        )
        assertEquals(
            LinkStatus.Row(Lamp.GREY, "空闲（没有指令）"),
            LinkStatus.bleRow(false, 0, 0, null, "")
        )
        assertEquals(
            LinkStatus.Row(Lamp.RED, "广播失败 errorCode=2（蓝牙没开/不支持）"),
            LinkStatus.bleRow(false, 0, 0, 2, "advertise_failed_2")
        )
        // 不是 AdvertiseCallback 给的错（没权限 / 蓝牙没开）就原样说
        assertEquals(
            LinkStatus.Row(Lamp.RED, "广播失败：蓝牙没开或本机不支持 BLE 广播"),
            LinkStatus.bleRow(false, 0, 0, null, "蓝牙没开或本机不支持 BLE 广播")
        )
    }

    @Test
    fun footnoteAlwaysSaysWeCannotSeeTheToy() {
        val none = LinkStatus.footnote(null, "")
        assertTrue(none.contains("玩具收没收到测不到"))
        assertTrue(none.contains("还没收到过指令"))

        val some = LinkStatus.footnote(7L, "吮吸 40")
        assertTrue(some.contains("玩具收没收到测不到"))
        assertEquals(true, some.endsWith("最后指令：7 秒前 吮吸 40"))
    }

    @Test
    fun describeCommandText() {
        assertEquals("全停", LinkStatus.describeCommand(0, 0))
        assertEquals("吮吸 40", LinkStatus.describeCommand(40, 0))
        assertEquals("入体 30", LinkStatus.describeCommand(0, 30))
        assertEquals("吮吸 40 / 入体 30", LinkStatus.describeCommand(40, 30))
    }
}
