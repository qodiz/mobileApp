package com.example.kwspotter

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Пустая служба специальных возможностей.
 *
 * Сама по себе она ничего не читает с экрана и не нажимает кнопки. Она нужна только
 * потому, что Android во время телефонного звонка глушит микрофон обычного приложения
 * (подсовывает тишину). Если у приложения включена такая служба, система разрешает ему
 * захватывать звук параллельно со звонком.
 *
 * Пользователь включает её вручную: Настройки → Специальные возможности → ClearCalls.
 * На Android 13+ для APK, установленного не из магазина, сначала в сведениях о приложении
 * нужно нажать «Разрешить ограниченные настройки».
 */
class CallCaptureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (active == this) active = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (active == this) active = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // События интерфейса не обрабатываем: служба существует ради аудиополитики.
    }

    override fun onInterrupt() {
        // Нечего прерывать: обратной связи пользователю служба не выдаёт.
    }

    companion object {
        private const val TAG = "CallCaptureA11y"

        @Volatile
        private var active: CallCaptureAccessibilityService? = null

        /** Подключённая служба. Её окно система рисует выше всех приложений. */
        fun connected(): CallCaptureAccessibilityService? = active

        /** true, если пользователь включил именно эту службу и мастер-переключатель спец. возможностей. */
        fun isEnabled(context: Context): Boolean {
            val resolver = context.contentResolver
            val masterEnabled = Settings.Secure.getInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
            if (!masterEnabled) return false

            val enabled = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val expected = ComponentName(context, CallCaptureAccessibilityService::class.java)
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                val component = ComponentName.unflattenFromString(splitter.next())
                if (component == expected) return true
            }
            return false
        }

        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
