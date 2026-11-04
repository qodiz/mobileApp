package com.example.kwspotter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** После перезагрузки и обновления снова поднимает фоновую службу. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> ListenerService.start(context)
        }
    }
}
