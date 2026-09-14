package dev.shikongbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 常驻前台服务：WebSocket 连 relay + 驱动广播层。
 * 断线每 3 秒重连（被别的手机顶掉就等 15 秒）；收到 state 覆盖本地并回 ack；
 * 收到 ping 回 pong；连上先发 hello。
 */
class BridgeService : Service() {

    private lateinit var settings: Settings
    private lateinit var engine: BroadcastEngine

    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var wantConnected = true
    private var generation = 0

    /** 服务端说"你被另一台手机顶下线了"。下一次重连要等久一点，别两台每 3 秒互踢。 */
    private var kickedByOther = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        engine = BroadcastEngine(
            context = this,
            connectableProvider = { settings.connectable },
            onError = { code, msg ->
                BridgeState.advErrorCode.value = code
                BridgeState.advError.value = msg
                BridgeState.advPlaying.value = false
                sendError(msg)
                updateNotification()
            },
            onAdvertising = { playing, text ->
                BridgeState.advPlaying.value = playing
                BridgeState.advText.value = text
                BridgeState.advError.value = ""
                BridgeState.advErrorCode.value = null
                updateNotification()
            },
        )
        BridgeLink.engine = engine
        BridgeLink.resetLocalEcho()
        createChannel()
        BridgeState.serviceRunning.value = true
        BridgeState.serviceStarting.value = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                // 先本地立刻走「吮吸关 → 总关」（断网也能停），再通知 relay
                engine.emergencyStop()
                BridgeState.suck.value = 0
                BridgeState.invib.value = 0
                BridgeLink.markFromRelay(0, 0)
                RelayHttp.postStop(settings) { msg -> BridgeState.lastError.value = msg }
            }

            ACTION_RECONNECT -> {
                closeWs("settings_changed")
                connect()
            }

            ACTION_SHUTDOWN -> {
                wantConnected = false
                engine.emergencyStop()
                // 等「吮吸关 + 总关」整套播完再收摊，否则吮吸端停不下来
                handler.postDelayed({
                    closeWs("user_shutdown")
                    stopForegroundCompat()
                    stopSelf()
                }, engine.stopAllDurationMs() + 200)
            }

            else -> {
                wantConnected = true
                if (ws == null) connect()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wantConnected = false
        closeWs("service_destroyed")
        BridgeLink.engine = null
        BridgeLink.resetLocalEcho()
        engine.shutdown()
        BridgeState.serviceRunning.value = false
        BridgeState.serviceStarting.value = false
        BridgeState.connected.value = false
        BridgeState.connecting.value = false
        BridgeState.advPlaying.value = false
        BridgeState.advText.value = "空闲"
        super.onDestroy()
    }

    // ---------- WebSocket ----------

    private fun connect() {
        if (!wantConnected) return
        if (!settings.hasToken) {
            // 口令不内置，没填过就别去撞 relay（撞了也是 401）
            BridgeState.connected.value = false
            BridgeState.connecting.value = false
            BridgeState.connFail.value = "还没设置口令，先在首页填一次"
            updateNotification()
            return
        }

        val gen = ++generation
        val url = RelayUrls.wsWithToken(settings.wsUrl, settings.token)
        BridgeState.connecting.value = true
        BridgeState.connFail.value = ""
        updateNotification()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer " + settings.token)
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                kickedByOther = false
                BridgeState.connected.value = true
                BridgeState.connecting.value = false
                BridgeState.kicked.value = false
                BridgeState.connFail.value = ""
                BridgeState.lastBeatMs.value = System.currentTimeMillis()
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("app_version", Settings.APP_VERSION)
                    .put("device", Build.MANUFACTURER + " " + Build.MODEL)
                    .put("connectable", settings.connectable)
                webSocket.send(hello.toString())
                updateNotification()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                handleMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation) return
                val why = if (reason.isBlank()) "服务器关闭了连接（code $code）"
                else "服务器关闭了连接（code $code：$reason）"
                dropped(why)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                dropped(describeFailure(t, response))
            }
        })
    }

    /** 把 OkHttp 的失败原样说清楚：HTTP code + 异常类名 + message。不许只写"未连接"。 */
    private fun describeFailure(t: Throwable, response: Response?): String {
        val parts = mutableListOf<String>()
        val code = response?.code
        if (code != null) {
            parts += when (code) {
                401 -> "HTTP 401 口令不对"
                403 -> "HTTP 403 被拒绝"
                404 -> "HTTP 404 地址不对（路径找不到）"
                502, 503, 504 -> "HTTP $code 服务器没在跑"
                else -> "HTTP $code"
            }
        }
        val msg = t.message
        parts += t.javaClass.simpleName + (if (msg.isNullOrBlank()) "" else ": $msg")
        return parts.joinToString(" / ")
    }

    private fun dropped(text: String) {
        BridgeState.connected.value = false
        BridgeState.connecting.value = false
        val kicked = kickedByOther
        kickedByOther = false
        val waitMs = if (kicked) KICKED_RECONNECT_MS else RECONNECT_MS
        BridgeState.kicked.value = kicked
        BridgeState.connFail.value = if (kicked) {
            "被另一台手机顶下线了，请关掉另一台上的失控桥"
        } else {
            text + "（" + (waitMs / 1000) + " 秒后重连）"
        }
        ws = null
        updateNotification()
        if (!wantConnected) return
        handler.postDelayed({ if (wantConnected && ws == null) connect() }, waitMs)
    }

    private fun closeWs(reason: String) {
        generation++
        try {
            ws?.close(1000, reason)
        } catch (_: Exception) {
        }
        ws = null
        BridgeState.connected.value = false
        BridgeState.connecting.value = false
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val msg = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        BridgeState.lastBeatMs.value = System.currentTimeMillis()

        when (msg.optString("type")) {
            "state" -> {
                val suck = msg.optInt("suck", 0)
                val invib = msg.optInt("invib", 0)
                val seq = msg.optLong("seq", 0L)
                BridgeState.suck.value = suck
                BridgeState.invib.value = invib
                BridgeState.seq.value = seq
                BridgeState.lastCmdMs.value = System.currentTimeMillis()
                BridgeState.lastCmdDesc.value = LinkStatus.describeCommand(suck, invib)
                // 本地优先：和本机已经在播的一致就别重播，重播只会白白打断广播
                if (BridgeLink.shouldApplyFromRelay(suck, invib)) {
                    BridgeLink.markFromRelay(suck, invib)
                    engine.applyState(suck, invib)
                }
                webSocket.send(JSONObject().put("type", "ack").put("seq", seq).toString())
                updateNotification()
            }

            "kicked" -> {
                // 服务端在把这条连接顶掉之前推的。两台手机 3 秒一次互踢会刷爆日志，
                // 所以标记一下，下面重连等 15 秒。
                kickedByOther = true
                BridgeState.kicked.value = true
                BridgeState.connFail.value = "被另一台手机顶下线了，请关掉另一台上的失控桥"
                updateNotification()
            }

            "ping" -> {
                val ts = msg.optLong("ts", System.currentTimeMillis())
                webSocket.send(JSONObject().put("type", "pong").put("ts", ts).toString())
            }
        }
    }

    private fun sendError(message: String) {
        try {
            ws?.send(JSONObject().put("type", "error").put("message", message).toString())
        } catch (_: Exception) {
        }
    }

    // ---------- 前台通知 ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "失控桥后台服务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持手机常驻发广播"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAll = PendingIntent.getService(
            this,
            1,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = when {
            BridgeState.connected.value -> "失控桥 · 在线"
            BridgeState.connecting.value -> "失控桥 · 连接中"
            else -> "失控桥 · 离线"
        }
        val body = if (BridgeState.connected.value) {
            "吮吸 " + BridgeState.suck.value + " · 入体 " + BridgeState.invib.value +
                " · " + BridgeState.advText.value
        } else {
            BridgeState.connFail.value.ifBlank { "未连接" }
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "全停", stopAll).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            // Android 14 起缺蓝牙权限时可能拒绝 connectedDevice 类型的前台服务
            BridgeState.lastError.value =
                "前台服务启动失败：" + e.javaClass.simpleName + ": " + (e.message ?: "")
            try {
                startForeground(NOTIFICATION_ID, n)
            } catch (_: Exception) {
            }
        }
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_STOP_ALL = "dev.shikongbridge.STOP_ALL"
        const val ACTION_RECONNECT = "dev.shikongbridge.RECONNECT"
        const val ACTION_SHUTDOWN = "dev.shikongbridge.SHUTDOWN"

        private const val CHANNEL_ID = "shikong_bridge"
        private const val NOTIFICATION_ID = 41
        private const val RECONNECT_MS = 3000L

        /** 被另一台手机顶下线后的重连间隔：等久点，免得两台每 3 秒互踢。 */
        private const val KICKED_RECONNECT_MS = 15000L

        fun start(context: Context) {
            BridgeState.serviceStarting.value = true
            send(context, null)
        }

        fun stopAll(context: Context) = send(context, ACTION_STOP_ALL)

        fun reconnect(context: Context) = send(context, ACTION_RECONNECT)

        fun shutdown(context: Context) = send(context, ACTION_SHUTDOWN)

        private fun send(context: Context, action: String?) {
            val intent = Intent(context, BridgeService::class.java)
            if (action != null) intent.action = action
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                BridgeState.serviceStarting.value = false
                BridgeState.lastError.value =
                    "服务启动失败：" + e.javaClass.simpleName + ": " + (e.message ?: "")
            }
        }
    }
}
