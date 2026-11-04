package com.example.kwspotter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Принимает шесть цифр из уведомления и завершает привязку к беспроводной отладке. */
class PairingCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotificationHelper.PAIRING_REMOTE_INPUT)
            ?.toString()
            ?.trim()
            .orEmpty()
        val pending = goAsync()
        PairingCoordinator.submitCode(context, code) { success, detail ->
            NotificationHelper.showPairingResult(context, success, detail)
            if (success) PairingCurtain.hide(context)
            pending.finish()
        }
    }
}
