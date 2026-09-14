package dev.shikongbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import dev.shikongbridge.ShikongProtocol.Cmd

/**
 * 广播层：拿 [BroadcastPlan] 排好的序列，真的去开关 BLE 广播。
 * 所有调度跑在自己的 HandlerThread 上，不占主线程。
 */
class BroadcastEngine(
    private val context: Context,
    private val connectableProvider: () -> Boolean,
    /** (errorCode, 人话) —— errorCode 只有 AdvertiseCallback 给的才有，其它情况是 null。 */
    private val onError: (Int?, String) -> Unit,
    /** (在不在播, 在播什么) */
    private val onAdvertising: (Boolean, String) -> Unit,
) {

    private val thread = HandlerThread("shikong-adv").apply { start() }
    private val handler = Handler(thread.looper)

    private val prelude = ArrayDeque<BroadcastPlan.Step>()
    private var cycle: List<Cmd> = emptyList()
    private var cycleIdx = 0

    private var curSuck = 0
    private var curInvib = 0

    private var activeCallback: AdvertiseCallback? = null
    private var everAdvertised = false

    private val advancer = Runnable { runNext() }

    /** relay 推来的新状态，覆盖本地。 */
    fun applyState(suck: Int, invib: Int) {
        handler.post {
            val newSuck = suck.coerceIn(0, 100)
            val newInvib = invib.coerceIn(0, 100)
            val plan = BroadcastPlan.forState(
                oldSuck = curSuck,
                oldInvib = curInvib,
                newSuck = newSuck,
                newInvib = newInvib,
                everAdvertised = everAdvertised,
            )
            curSuck = newSuck
            curInvib = newInvib
            load(plan)
        }
    }

    /**
     * 全停：本地立刻走「吮吸关 1.2 秒 → 总关 1.5 秒 → 停播」（断网也能停）。
     * 只发总关的话吮吸端停不下来，真机实测过。
     */
    fun emergencyStop() {
        handler.post {
            curSuck = 0
            curInvib = 0
            load(BroadcastPlan.stopAll())
        }
    }

    /** 全停序列总共要多久（调用方等这么久再关服务）。 */
    fun stopAllDurationMs(): Long = BroadcastPlan.stopAll().prelude.sumOf { it.durationMs }

    fun shutdown() {
        handler.post {
            prelude.clear()
            cycle = emptyList()
            stopAdvertising()
            thread.quitSafely()
        }
    }

    private fun load(plan: BroadcastPlan.Plan) {
        prelude.clear()
        plan.prelude.forEach { prelude.addLast(it) }
        cycle = plan.cycle
        cycleIdx = 0
        runNext()
    }

    private fun runNext() {
        handler.removeCallbacks(advancer)
        stopAdvertising()

        val step = prelude.removeFirstOrNull()
        if (step != null) {
            startAdvertise(step.cmd)
            handler.postDelayed(advancer, step.durationMs)
            return
        }

        val c = cycle
        when (c.size) {
            0 -> onAdvertising(false, "空闲")
            1 -> startAdvertise(c[0]) // 只有一条就一直播着，不用换
            else -> {
                val cmd = c[cycleIdx % c.size]
                cycleIdx = (cycleIdx + 1) % c.size
                startAdvertise(cmd)
                handler.postDelayed(advancer, BroadcastPlan.ROTATE_MS)
            }
        }
    }

    private fun describe(cmd: Cmd): String = when (cmd) {
        is Cmd.Suck -> "吮吸 ${cmd.level}"
        is Cmd.Invib -> "入体 ${cmd.level}"
        Cmd.SuckOff -> "吮吸关"
        Cmd.AllOff -> "总关"
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertise(cmd: Cmd) {
        if (!hasAdvertisePermission()) {
            onError(null, "缺少蓝牙广播权限")
            return
        }
        val advertiser = advertiser()
        if (advertiser == null) {
            onError(null, "蓝牙没开或本机不支持 BLE 广播")
            return
        }

        val uuidText = ShikongProtocol.build(cmd)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(connectableProvider())
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(ShikongProtocol.asUuid(uuidText)))
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                onAdvertising(true, describe(cmd))
            }

            override fun onStartFailure(errorCode: Int) {
                onError(errorCode, "advertise_failed_$errorCode")
            }
        }

        try {
            advertiser.startAdvertising(settings, data, callback)
            activeCallback = callback
            everAdvertised = true
        } catch (e: Exception) {
            onError(null, "广播启动异常：" + e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        val cb = activeCallback ?: return
        activeCallback = null
        if (!hasAdvertisePermission()) return
        try {
            advertiser()?.stopAdvertising(cb)
        } catch (_: Exception) {
            // 蓝牙被关掉时会抛，忽略
        }
    }

    private fun advertiser(): BluetoothLeAdvertiser? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bluetoothLeAdvertiser
    }

    private fun hasAdvertisePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED
    }
}
