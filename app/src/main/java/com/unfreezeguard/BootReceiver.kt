package com.unfreezeguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            PkgStore.loadSync(context).isNotEmpty()
        ) {
            context.startForegroundService(
                Intent(context, GuardService::class.java).setAction(GuardService.ACTION_START)
            )
        }
    }
}
