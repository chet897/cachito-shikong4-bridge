package dev.shikongbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Ui.accent,
                    onPrimary = Color.White,
                    background = Ui.bgMid,
                    onBackground = Ui.text,
                    surface = Ui.bgMid,
                    onSurface = Ui.text,
                    outline = Ui.textFaint,
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Ui.bgTop, Ui.bgMid, Ui.bgBottom)
                            )
                        )
                ) {
                    App(settings)
                }
            }
        }
    }
}

private fun neededPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * 两页：连接 / 控制。设置是从连接页进去的整页。
 */
@Composable
private fun App(settings: Settings) {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf("main") }
    var tab by remember { mutableIntStateOf(0) }
    var asked by remember { mutableIntStateOf(0) }

    // 只申请权限，**不自动启动后台服务**
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        if (asked == 0) {
            asked = 1
            val missing = neededPermissions().filter {
                ctx.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
        }
    }

    if (page == "settings") {
        SettingsPage(settings, onBack = { page = "main" })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Ui.pagePad)
            .padding(top = 20.dp)
    ) {
        GradientTitle("🜂 失控桥")
        Spacer(Modifier.height(14.dp))
        TabSwitch(listOf("连接", "控制"), tab) { tab = it }
        Spacer(Modifier.height(Ui.cardGap))
        Box(Modifier.weight(1f)) {
            if (tab == 0) {
                ConnectPage(settings, onSettings = { page = "settings" })
            } else {
                ControlPage(settings)
            }
        }
    }
}

// ---------------- 第一页：连接 ----------------

@Composable
private fun ConnectPage(settings: Settings, onSettings: () -> Unit) {
    val ctx = LocalContext.current

    val running by BridgeState.serviceRunning.collectAsState()
    val starting by BridgeState.serviceStarting.collectAsState()
    val connected by BridgeState.connected.collectAsState()
    val connecting by BridgeState.connecting.collectAsState()
    val kicked by BridgeState.kicked.collectAsState()
    val connFail by BridgeState.connFail.collectAsState()
    val lastBeatMs by BridgeState.lastBeatMs.collectAsState()
    val suck by BridgeState.suck.collectAsState()
    val invib by BridgeState.invib.collectAsState()
    val advPlaying by BridgeState.advPlaying.collectAsState()
    val advErrorCode by BridgeState.advErrorCode.collectAsState()
    val advError by BridgeState.advError.collectAsState()
    val lastCmdMs by BridgeState.lastCmdMs.collectAsState()
    val lastCmdDesc by BridgeState.lastCmdDesc.collectAsState()
    val lastError by BridgeState.lastError.collectAsState()

    var hasToken by remember { mutableStateOf(settings.hasToken) }
    var tokenExpanded by remember { mutableStateOf(!settings.hasToken) }
    var tokenDraft by remember { mutableStateOf("") }

    // 每 1 秒刷新（"x 秒前"要走字）
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val nowMs = remember(tick) { System.currentTimeMillis() }
    fun agoS(ms: Long): Long? =
        if (ms <= 0L) null else ((nowMs - ms) / 1000).coerceAtLeast(0L)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ---- ① 链路状态（蓝） ----
        SectionCard("链路状态", Ui.linkBg, Ui.linkBorder, Ui.linkTitle) {
            StatusRow("① 口令", LinkStatus.tokenRow(hasToken)) { tokenExpanded = !tokenExpanded }
            if (tokenExpanded) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    label = { Text("输入口令（问 VPS 助手要）", fontSize = Ui.noteSp) }
                )
                Spacer(Modifier.height(8.dp))
                Note("口令没内置在安装包里（内置会被第三方扫描程序捡去用）。填一次就存本机。")
                Spacer(Modifier.height(10.dp))
                PrimaryButton("保存", enabled = tokenDraft.isNotBlank(), onClick = {
                    settings.token = tokenDraft
                    hasToken = settings.hasToken
                    if (hasToken) {
                        tokenDraft = ""
                        tokenExpanded = false
                        BridgeState.lastError.value = ""
                        if (running) BridgeService.reconnect(ctx)
                    }
                })
            }

            Spacer(Modifier.height(10.dp))
            StatusRow("② 后台服务", LinkStatus.serviceRow(running, starting))
            Spacer(Modifier.height(10.dp))
            StatusRow(
                "③ VPS 连接",
                LinkStatus.vpsRow(connected, connecting, kicked, connFail, agoS(lastBeatMs))
            )
            Spacer(Modifier.height(10.dp))
            StatusRow(
                "④ 蓝牙广播",
                LinkStatus.bleRow(advPlaying, suck, invib, advErrorCode, advError)
            )

            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Ui.divider)
            )
            Spacer(Modifier.height(10.dp))
            Note(LinkStatus.footnote(agoS(lastCmdMs), lastCmdDesc))

            if (lastError.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text("⚠ $lastError", fontSize = Ui.noteSp, color = Ui.lampRed)
            }
        }

        Spacer(Modifier.height(Ui.cardGap))

        // ---- ② 后台服务（粉） ----
        SectionCard("后台服务", Ui.ctrlBg, Ui.ctrlBorder, Ui.ctrlTitle) {
            PrimaryButton(
                label = if (running) "停止后台服务" else "启动后台服务",
                enabled = hasToken,
                onClick = { if (running) BridgeService.shutdown(ctx) else BridgeService.start(ctx) }
            )
            Spacer(Modifier.height(10.dp))
            Note("不会自己启动：每次要用，自己来这一页按一下。启动后通知栏常驻，可以锁屏。")
        }

        Spacer(Modifier.height(Ui.cardGap))

        // ---- ③ 设置（灰） ----
        SectionCard("设置", Ui.setBg, Ui.setBorder, Ui.setTitle) {
            GhostButton("服务器地址 / 口令 / 可连接广播", onClick = onSettings)
            Spacer(Modifier.height(10.dp))
            GhostButton("后台高耗电 / 自启动", onClick = { openAppDetails(ctx) })
            Spacer(Modifier.height(10.dp))
            Note(
                "iQOO / vivo 记得开两项：应用详情 → 耗电管理 → 允许后台高耗电；" +
                    "i 管家 → 应用管理 → 自启动管理 → 打开「失控桥」。不开的话锁屏一会儿后台就被杀。"
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ---------------- 设置页 ----------------

@Composable
private fun SettingsPage(settings: Settings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(settings.wsUrl) }
    var token by remember { mutableStateOf(settings.token) }
    var connectable by remember { mutableStateOf(settings.connectable) }
    var saved by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Ui.pagePad, vertical = 20.dp)
    ) {
        GradientTitle("设置")
        Spacer(Modifier.height(Ui.cardGap))

        SectionCard("服务器", Ui.linkBg, Ui.linkBorder, Ui.linkTitle) {
            Body("WebSocket 地址")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Note(
                "默认（直连 VPS）" + Settings.DEFAULT_WS_URL +
                    "\n隧道通了以后改回 " + Settings.TUNNEL_WS_URL
            )

            Spacer(Modifier.height(14.dp))
            Body("口令")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Note("口令不内置在安装包里，得自己填一次（问 VPS 助手要）。填完存在本机。")
        }

        Spacer(Modifier.height(Ui.cardGap))

        SectionCard("广播", Ui.ctrlBg, Ui.ctrlBorder, Ui.ctrlTitle) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.fillMaxWidth(0.72f)) {
                    Body("可连接广播")
                    Spacer(Modifier.height(4.dp))
                    Note("默认开。失控一般要可连接广播才认；个别手机不支持时关掉试试。")
                }
                Switch(checked = connectable, onCheckedChange = { connectable = it })
            }
        }

        Spacer(Modifier.height(Ui.cardGap))

        SectionCard("系统", Ui.setBg, Ui.setBorder, Ui.setTitle) {
            GhostButton("打开「后台高耗电允许 / 自启动」设置", onClick = { openAppDetails(ctx) })
            Spacer(Modifier.height(8.dp))
            Note("进去后：耗电管理（电池）→ 允许后台高耗电；自启动要去 i 管家 → 应用管理 → 自启动管理。")
            Spacer(Modifier.height(12.dp))
            GhostButton("申请忽略电池优化", onClick = { requestIgnoreBattery(ctx) })
            Spacer(Modifier.height(12.dp))
            GhostButton(
                "停止后台服务（先播全停）",
                onClick = { BridgeService.shutdown(ctx) },
                color = Ui.dangerTitle,
                border = Ui.dangerBorder
            )
        }

        Spacer(Modifier.height(Ui.cardGap))

        PrimaryButton("保存并重连", onClick = {
            settings.wsUrl = url
            settings.token = token
            settings.connectable = connectable
            BridgeService.reconnect(ctx)
            saved = "已保存并重新连接"
        })
        if (saved.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(saved, fontSize = Ui.noteSp, color = Ui.lampGreen)
        }

        Spacer(Modifier.height(10.dp))
        GhostButton("返回", onClick = onBack)

        Spacer(Modifier.height(16.dp))
        Note("版本 " + Settings.APP_VERSION)
        Spacer(Modifier.height(24.dp))
    }
}

private fun openAppDetails(ctx: Context) {
    try {
        val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    } catch (e: Exception) {
        BridgeState.lastError.value = "打不开系统设置页：" + (e.message ?: "")
    }
}

@android.annotation.SuppressLint("BatteryLife")
private fun requestIgnoreBattery(ctx: Context) {
    try {
        val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    } catch (e: Exception) {
        BridgeState.lastError.value = "打不开电池优化页：" + (e.message ?: "")
    }
}
