package dev.shikongbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** 快捷档位。0 显示成「停」。 */
private val LEVELS = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)

/** 拖动滑块时每隔这么久往本机广播引擎推一次。 */
private const val DRAG_THROTTLE_MS = 150L

/** 拉一次 /api/state 的间隔：WS 推的 state 没有 pattern 字段，只能轮询。 */
private const val STATE_POLL_MS = 2000L

/**
 * 用户手动接管某一端之后，这么久之内不听轮询回来的「还在跑模式」。
 * relay 收到 /api/set 才会清模式，轮询有可能抢在前面把旧值刷回来，一闪一闪很吓人。
 */
private const val PATTERN_MUTE_MS = 2500L

/**
 * 第二页「控制」。0.1.5 起的布局：
 * 状态行 → 全停 → 吮吸卡 → 入体卡 → 模式卡。
 */
@Composable
internal fun ControlPage(settings: Settings) {
    val ctx = LocalContext.current
    val connected by BridgeState.connected.collectAsState()
    val running by BridgeState.serviceRunning.collectAsState()
    val suck by BridgeState.suck.collectAsState()
    val invib by BridgeState.invib.collectAsState()
    val suckPat by BridgeState.suckPattern.collectAsState()
    val invibPat by BridgeState.invibPattern.collectAsState()
    val patterns by BridgeState.patterns.collectAsState()
    val patternsError by BridgeState.patternsError.collectAsState()
    val lastCmdMs by BridgeState.lastCmdMs.collectAsState()
    val lastCmdDesc by BridgeState.lastCmdDesc.collectAsState()
    val lastError by BridgeState.lastError.collectAsState()

    var suckDrag by remember { mutableStateOf<Float?>(null) }
    var invibDrag by remember { mutableStateOf<Float?>(null) }
    var suckThrottleMs by remember { mutableStateOf(0L) }
    var invibThrottleMs by remember { mutableStateOf(0L) }
    // 手动接管后压住轮询的截止时刻，见 PATTERN_MUTE_MS。
    // 用 AtomicLong 不用 Compose state：它只在 OkHttp 回调线程被读，不需要触发重组。
    val suckMuteUntilMs = remember { AtomicLong(0L) }
    val invibMuteUntilMs = remember { AtomicLong(0L) }

    // "x 秒前"要走字
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val agoS = remember(tick, lastCmdMs) {
        if (lastCmdMs <= 0L) null
        else ((System.currentTimeMillis() - lastCmdMs) / 1000).coerceAtLeast(0L)
    }

    // 进页面就拉一次模式表，然后每 2 秒拉一次状态（拿"在跑哪个模式"）。
    // 离开这一页（切到连接页）协程就被取消，不再空转。
    LaunchedEffect(Unit) {
        RelayHttp.getPatterns(
            settings,
            onOk = {
                BridgeState.patterns.value = it
                BridgeState.patternsError.value = if (it.isEmpty()) "服务器给的模式表是空的" else ""
            },
            onFail = { BridgeState.patternsError.value = it },
        )
        while (true) {
            RelayHttp.getState(
                settings,
                onOk = { snap ->
                    // 只认模式名。档位以 WebSocket 推的为准（本地优先那一路会被轮询拖慢）。
                    val now = System.currentTimeMillis()
                    if (now >= suckMuteUntilMs.get()) {
                        BridgeState.suckPattern.value = snap.suckPattern
                    }
                    if (now >= invibMuteUntilMs.get()) {
                        BridgeState.invibPattern.value = snap.invibPattern
                    }
                },
                onFail = { },
            )
            delay(STATE_POLL_MS)
        }
    }

    /** 用户碰了吮吸端 = 接管：立刻取消模式接管的灰态，并压住轮询一会儿。 */
    fun takeOverSuck() {
        suckMuteUntilMs.set(System.currentTimeMillis() + PATTERN_MUTE_MS)
        BridgeState.suckPattern.value = null
    }

    fun takeOverInvib() {
        invibMuteUntilMs.set(System.currentTimeMillis() + PATTERN_MUTE_MS)
        BridgeState.invibPattern.value = null
    }

    /** 手动设档：本机立刻播，再告诉 relay；relay 会把该通道的模式清掉。 */
    fun applySuck(v: Int) {
        suckDrag = null
        BridgeState.suck.value = v
        takeOverSuck()
        BridgeLink.applyLocal(v, (invibDrag ?: invib.toFloat()).roundToInt())
        RelayHttp.postSet(settings, suck = v, invib = null) { BridgeState.lastError.value = it }
    }

    fun applyInvib(v: Int) {
        invibDrag = null
        BridgeState.invib.value = v
        takeOverInvib()
        BridgeLink.applyLocal((suckDrag ?: suck.toFloat()).roundToInt(), v)
        RelayHttp.postSet(settings, suck = null, invib = v) { BridgeState.lastError.value = it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ---- 顶部状态：手机在线灯 + 最后指令 ----
        SectionCard("手机 App", Ui.linkBg, Ui.linkBorder, Ui.linkTitle) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lamp(if (connected) Ui.lampGreen else Ui.lampRed)
                Spacer(Modifier.width(10.dp))
                Body(
                    when {
                        connected -> "在线，指令能播出去"
                        running -> "没连上 VPS，去「连接」页看哪一环断了"
                        else -> "后台服务没启动，去「连接」页启动"
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            Note(
                if (agoS == null || lastCmdDesc.isBlank()) "最后指令：还没收到过"
                else "最后指令：$agoS 秒前 $lastCmdDesc"
            )
            if (lastError.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("⚠ $lastError", fontSize = Ui.noteSp, color = Ui.lampRed)
            }
        }

        Spacer(Modifier.height(Ui.cardGap))

        // ---- 全停（0.1.5 挪到最上面，手一伸就够得着）----
        GhostButton(
            "全停",
            onClick = {
                suckDrag = null
                invibDrag = null
                takeOverSuck()
                takeOverInvib()
                BridgeService.stopAll(ctx)
            },
            color = Ui.dangerTitle,
            border = Ui.dangerBorder,
        )
        Spacer(Modifier.height(6.dp))
        Note("先在本机播「吮吸关 1.2 秒 → 总关 1.5 秒」，再通知服务器；断网也能停。")

        Spacer(Modifier.height(Ui.cardGap))

        // ---- 吮吸 ----
        SectionCard(
            "🌸 吮吸端", Ui.ctrlBg, Ui.ctrlBorder, Ui.ctrlTitle,
            trailing = { EndStatus(suckPat, (suckDrag ?: suck.toFloat()).roundToInt()) },
        ) {
            LevelSlider(
                value = suckDrag ?: suck.toFloat(),
                dimmed = suckPat != null,
                onDrag = { f ->
                    // 一碰就接管：模式灰态立刻消失，后面照旧走本地优先
                    if (suckPat != null) takeOverSuck()
                    suckDrag = f
                    val now = System.currentTimeMillis()
                    if (now - suckThrottleMs >= DRAG_THROTTLE_MS) {
                        suckThrottleMs = now
                        BridgeLink.applyLocal(
                            f.roundToInt(),
                            (invibDrag ?: invib.toFloat()).roundToInt()
                        )
                    }
                },
                onRelease = { applySuck((suckDrag ?: suck.toFloat()).roundToInt()) },
            )
            Spacer(Modifier.height(4.dp))
            Note("快捷档位")
            Spacer(Modifier.height(6.dp))
            LevelChips(current = suck, patternOn = suckPat != null, onPick = { applySuck(it) })
        }

        Spacer(Modifier.height(Ui.cardGap))

        // ---- 入体 ----
        SectionCard(
            "💫 入体端", Ui.ctrlBg, Ui.ctrlBorder, Ui.ctrlTitle,
            trailing = { EndStatus(invibPat, (invibDrag ?: invib.toFloat()).roundToInt()) },
        ) {
            LevelSlider(
                value = invibDrag ?: invib.toFloat(),
                dimmed = invibPat != null,
                onDrag = { f ->
                    if (invibPat != null) takeOverInvib()
                    invibDrag = f
                    val now = System.currentTimeMillis()
                    if (now - invibThrottleMs >= DRAG_THROTTLE_MS) {
                        invibThrottleMs = now
                        BridgeLink.applyLocal(
                            (suckDrag ?: suck.toFloat()).roundToInt(),
                            f.roundToInt()
                        )
                    }
                },
                onRelease = { applyInvib((invibDrag ?: invib.toFloat()).roundToInt()) },
            )
            Spacer(Modifier.height(4.dp))
            Note("快捷档位")
            Spacer(Modifier.height(6.dp))
            LevelChips(current = invib, patternOn = invibPat != null, onPick = { applyInvib(it) })
        }

        Spacer(Modifier.height(Ui.cardGap))

        // ---- 模式 ----
        SectionCard("🎐 模式", Ui.ctrlBg, Ui.ctrlBorder, Ui.ctrlTitle) {
            Note("每行三个按钮：只套吮吸端 / 只套入体端 / 两端一起。模式在 VPS 上跑，手机只管播。\n想停某一端的模式：直接拖那一端的滑块（或点快捷档位）。")
            Spacer(Modifier.height(6.dp))

            if (patterns.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (patternsError.isBlank()) "正在取模式表…" else "取模式表失败：$patternsError",
                    fontSize = Ui.noteSp,
                    color = if (patternsError.isBlank()) Ui.textDim else Ui.lampRed,
                )
            } else {
                patterns.forEachIndexed { i, p ->
                    if (i > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Ui.divider)
                        )
                    }
                    PatternRow(
                        p = p,
                        onSuck = suckPat == p.name,
                        onInvib = invibPat == p.name,
                        onRun = { part ->
                            // 先本地高亮，2 秒内轮询会用服务器的真值校正
                            if (part == "suck" || part == "both") {
                                suckMuteUntilMs.set(0L)
                                BridgeState.suckPattern.value = p.name
                            }
                            if (part == "invib" || part == "both") {
                                invibMuteUntilMs.set(0L)
                                BridgeState.invibPattern.value = p.name
                            }
                            RelayHttp.postPattern(settings, part, p.name) {
                                BridgeState.lastError.value = it
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 卡片标题右边那行小字：这一端现在归谁管、当前几档。 */
@Composable
private fun EndStatus(pattern: String?, level: Int) {
    Text(
        if (pattern != null) "模式「$pattern」控制中 · 当前 $level" else "手动 · $level",
        fontSize = Ui.noteSp,
        fontWeight = FontWeight.Bold,
        color = if (pattern != null) Ui.chipOnText else Ui.textDim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 0/10/20…100 的快捷档位按钮，两行。跑模式时不高亮任何一个。 */
@Composable
private fun LevelChips(current: Int, patternOn: Boolean, onPick: (Int) -> Unit) {
    val rows = LEVELS.chunked(6)
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { v ->
                    Chip(
                        label = if (v == 0) "停" else v.toString(),
                        on = !patternOn && current == v,
                        modifier = Modifier.weight(1f),
                    ) { onPick(v) }
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 一个模式：名字 + 三个小按钮，下面一行小字说明。 */
@Composable
private fun PatternRow(
    p: Pattern,
    onSuck: Boolean,
    onInvib: Boolean,
    onRun: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                p.name,
                fontSize = Ui.bodySp,
                fontWeight = FontWeight.Bold,
                color = if (onSuck || onInvib) Ui.chipOnText else Ui.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Chip("吮吸端", onSuck, Modifier.width(52.dp)) { onRun("suck") }
            Spacer(Modifier.width(4.dp))
            Chip("入体端", onInvib, Modifier.width(52.dp)) { onRun("invib") }
            Spacer(Modifier.width(4.dp))
            Chip("两端", onSuck && onInvib, Modifier.width(42.dp)) { onRun("both") }
        }
        val sub = patternSub(p)
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                sub,
                fontSize = Ui.noteSp,
                color = Ui.textFaint,
                lineHeight = Ui.noteSp * 1.4f,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 模式下面那行小字：优先用服务器给的 desc，没有就自己拼一圈几秒。 */
private fun patternSub(p: Pattern): String {
    if (p.desc.isNotBlank()) return p.desc
    val parts = mutableListOf<String>()
    if (p.suckCycleS > 0) parts += "吮吸 " + trimS(p.suckCycleS) + " 秒一圈"
    if (p.invibCycleS > 0) parts += "入体 " + trimS(p.invibCycleS) + " 秒一圈"
    return parts.joinToString(" · ")
}

private fun trimS(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)
