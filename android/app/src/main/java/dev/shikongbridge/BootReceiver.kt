package dev.shikongbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启（vivo/iQOO 还需要在 i 管家里手动允许自启动，光有这个不够）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // 没设过口令就别自己连上去（口令不内置）
        if (!Settings(context).hasToken) return
        BridgeService.start(context)
    }
}
