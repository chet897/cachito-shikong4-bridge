package dev.shikongbridge

/**
 * 首页「链路状态」四行的文案映射。纯 JVM、无 Android 依赖，方便单元测试。
 *
 * 用户原话：「到底哪一步断了没说清：口令对不对、手机连没连上 VPS、蓝牙发没发出去」。
 * 所以这四行要一眼看出断在哪一环。
 */
object LinkStatus {

    enum class Lamp { GREEN, RED, GREY }

    data class Row(val lamp: Lamp, val text: String)

    /** ① 口令 */
    fun tokenRow(hasToken: Boolean): Row =
        if (hasToken) Row(Lamp.GREEN, "已填")
        else Row(Lamp.RED, "没填，点这里填")

    /** ② 后台服务 */
    fun serviceRow(running: Boolean, starting: Boolean): Row = when {
        running -> Row(Lamp.GREEN, "运行中")
        starting -> Row(Lamp.GREY, "启动中…")
        else -> Row(Lamp.RED, "没启动，点下面按钮启动")
    }

    /**
     * ③ VPS 连接
     *
     * @param lastBeatAgoS 距上次收到心跳/状态多少秒，没收到过给 null
     * @param failReason   连不上的**具体**原因（OkHttp 的 response code、异常类名+message），
     *                     不许只写"未连接"
     */
    fun vpsRow(
        connected: Boolean,
        connecting: Boolean,
        kicked: Boolean,
        failReason: String,
        lastBeatAgoS: Long?,
    ): Row = when {
        connected -> Row(
            Lamp.GREEN,
            if (lastBeatAgoS == null) "已连上（还没收到心跳）"
            else "已连上（$lastBeatAgoS 秒前收到心跳）"
        )

        kicked -> Row(Lamp.RED, "连不上：被另一台手机顶下线了，请关掉另一台上的失控桥")
        connecting -> Row(Lamp.GREY, "连接中…")
        failReason.isNotBlank() -> Row(Lamp.RED, "连不上：$failReason")
        else -> Row(Lamp.RED, "连不上：还没开始连")
    }

    /**
     * ④ 蓝牙广播
     *
     * @param errorCode AdvertiseCallback 给的 errorCode，不是它给的错（没权限/蓝牙没开）就传 null
     */
    fun bleRow(
        playing: Boolean,
        suck: Int,
        invib: Int,
        errorCode: Int?,
        errorMessage: String,
    ): Row = when {
        errorMessage.isNotBlank() && errorCode != null ->
            Row(Lamp.RED, "广播失败 errorCode=$errorCode（蓝牙没开/不支持）")

        errorMessage.isNotBlank() -> Row(Lamp.RED, "广播失败：$errorMessage")
        playing -> Row(Lamp.GREEN, "正在播：吮吸 $suck / 入体 $invib")
        else -> Row(Lamp.GREY, "空闲（没有指令）")
    }

    /** 四行下面那句小字。广播是单向的，收没收到测不出来，得说清楚。 */
    fun footnote(lastCmdAgoS: Long?, lastCmdDesc: String): String {
        val head = "玩具收没收到测不到（广播是单向的），以玩具实际反应为准。"
        return if (lastCmdAgoS == null || lastCmdDesc.isBlank()) {
            head + "还没收到过指令。"
        } else {
            head + "最后指令：$lastCmdAgoS 秒前 $lastCmdDesc"
        }
    }

    /** 把 relay 推来的档位说成人话，给上面那句小字用。 */
    fun describeCommand(suck: Int, invib: Int): String = when {
        suck == 0 && invib == 0 -> "全停"
        invib == 0 -> "吮吸 $suck"
        suck == 0 -> "入体 $invib"
        else -> "吮吸 $suck / 入体 $invib"
    }
}
