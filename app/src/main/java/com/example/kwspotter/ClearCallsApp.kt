package com.example.kwspotter

import android.app.Application
import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.PRNGFixes
import org.conscrypt.Conscrypt
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.security.Security

/**
 * Запускается системой раньше любой Activity.
 *
 * Две вещи нужны клиенту беспроводной отладки:
 * сертификат ADB строится скрытыми классами android.sun.security (их надо
 * разблокировать до первой загрузки), а само подключение говорит на TLS 1.3,
 * который даёт Conscrypt.
 */
class ClearCallsApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }

    override fun onCreate() {
        super.onCreate()
        PRNGFixes.apply()
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
        NotificationHelper.createChannels(this)
        ListenerService.start(this)
    }
}
