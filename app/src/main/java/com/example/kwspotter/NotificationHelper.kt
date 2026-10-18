package com.example.kwspotter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

object NotificationHelper {

    const val ALERT_CHANNEL_ID = "keyword_alert_channel"
    const val SERVICE_CHANNEL_ID = "listener_service_channel"
    const val PAIRING_CHANNEL_ID = "wifi_debug_pairing"

    private const val ALERT_NOTIFICATION_ID = 1001
    const val SERVICE_NOTIFICATION_ID = 2001
    const val PAIRING_NOTIFICATION_ID = 2002
    const val PAIRING_OVERLAY_NOTIFICATION_ID = 2003
    const val PAIRING_REMOTE_INPUT = "pairing_code"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            context.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.alert_channel_description)
            enableVibration(true)
        }

        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            context.getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.service_channel_description)
        }

        val pairingChannel = NotificationChannel(
            PAIRING_CHANNEL_ID,
            context.getString(R.string.pairing_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.pairing_notification_text)
            setShowBadge(false)
        }

        manager.createNotificationChannel(alertChannel)
        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(pairingChannel)
    }

    fun buildServiceNotification(context: Context, text: String): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.service_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun buildPairingOverlayNotification(context: Context): android.app.Notification {
        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.pairing_notification_title))
            .setContentText(context.getString(R.string.pairing_overlay_notification))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Уведомление с полем ввода: код можно вписать, не закрывая системное окно привязки. */
    fun showPairingPrompt(context: Context) {
        val input = RemoteInput.Builder(PAIRING_REMOTE_INPUT)
            .setLabel(context.getString(R.string.pairing_code_label))
            .build()
        val pending = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, PairingCodeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_input_add,
            context.getString(R.string.pairing_enter_code),
            pending
        ).addRemoteInput(input).build()

        val notification = NotificationCompat.Builder(context, PAIRING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.pairing_notification_title))
            .setContentText(context.getString(R.string.pairing_notification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .addAction(action)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(PAIRING_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    fun showPairingResult(context: Context, success: Boolean, detail: String?) {
        val notification = NotificationCompat.Builder(context, PAIRING_CHANNEL_ID)
            .setSmallIcon(
                if (success) android.R.drawable.ic_dialog_info
                else android.R.drawable.ic_dialog_alert
            )
            .setContentTitle(
                context.getString(
                    if (success) R.string.pairing_success else R.string.pairing_failed
                )
            )
            .setContentText(detail ?: "")
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(PAIRING_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    fun showKeywordDetected(context: Context) {
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.alert_notification_title))
            .setContentText(context.getString(R.string.alert_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 250, 150, 250))
            .build()

        NotificationManagerCompat.from(context).notify(ALERT_NOTIFICATION_ID, notification)
        vibrate(context)
    }

    fun vibrate(context: Context) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(300)
        }
    }
}
