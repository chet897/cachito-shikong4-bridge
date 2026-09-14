package dev.shikongbridge

import kotlinx.coroutines.flow.MutableStateFlow

/** 界面和服务之间共享的实时状态（服务写，界面读）。 */
object BridgeState {

    // ---- ② 后台服务 ----
    val serviceRunning = MutableStateFlow(false)
    val serviceStarting = MutableStateFlow(false)

    // ---- ③ VPS 连接 ----
    val connected = MutableStateFlow(false)
    val connecting = MutableStateFlow(false)
    val kicked = MutableStateFlow(false)

    /** 连不上的具体原因：OkHttp 的 response code、异常类名+message，原样存。空串 = 没失败过。 */
    val connFail = MutableStateFlow("")

    /** 上次收到心跳（ping）或状态（state）的时刻，0 = 没收到过。 */
    val lastBeatMs = MutableStateFlow(0L)

    // ---- relay 推过来的档位（relay 是唯一真身） ----
    val suck = MutableStateFlow(0)
    val invib = MutableStateFlow(0)
    val seq = MutableStateFlow(0L)

    /**
     * 本机**已经在播**的档位（本地优先滑块写、relay 推来的 state 也会写）。
     * -1 = 未知。relay 推来的值和这俩一样就不用重播，见 [BridgeLink]。
     */
    val localSuck = MutableStateFlow(BridgeLink.UNKNOWN)
    val localInvib = MutableStateFlow(BridgeLink.UNKNOWN)

    /** 上次收到 state 的时刻和内容，给首页"最后指令"那行用。 */
    val lastCmdMs = MutableStateFlow(0L)
    val lastCmdDesc = MutableStateFlow("")

    // ---- 模式（控制页拉 /api/patterns、/api/state 得来） ----

    /** relay 上的模式表，进控制页拉一次。 */
    val patterns = MutableStateFlow<List<Pattern>>(emptyList())

    /** 拉模式表失败的原因，空串 = 没失败过。 */
    val patternsError = MutableStateFlow("")

    /** 两个通道各自在跑的模式名，null = 没跑模式。WS 推的 state 没这字段，只能轮询拿。 */
    val suckPattern = MutableStateFlow<String?>(null)
    val invibPattern = MutableStateFlow<String?>(null)

    // ---- ④ 蓝牙广播 ----
    val advPlaying = MutableStateFlow(false)
    val advText = MutableStateFlow("空闲")

    /** AdvertiseCallback 给的 errorCode；不是它给的错就是 null。 */
    val advErrorCode = MutableStateFlow<Int?>(null)
    val advError = MutableStateFlow("")

    /** 其它杂项错误（发 HTTP 失败、服务起不来等），给首页红字用。 */
    val lastError = MutableStateFlow("")
}
