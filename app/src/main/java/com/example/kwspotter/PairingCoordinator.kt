package com.example.kwspotter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Ищет порт привязки, пока на экране открыто системное окно с шестизначным кодом.
 * Цифры вводятся в панели «Отладка по Wi‑Fi», не уходя с этого окна.
 */
object PairingCoordinator {

    private const val TAG = "PairingCoordinator"

    private val endpoint = AtomicReference<Pair<String, Int>?>(null)
    private val ready = AtomicReference(CountDownLatch(1))
    private val main = Handler(Looper.getMainLooper())
    private val serviceWaits = AtomicInteger(0)

    fun begin(context: Context) {
        val app = context.applicationContext
        val service = CallCaptureAccessibilityService.connected()
        if (service == null) {
            // Служба только что включена и ещё не успела подключиться.
            if (CallCaptureAccessibilityService.isEnabled(app) && serviceWaits.get() < 15) {
                serviceWaits.incrementAndGet()
                main.postDelayed({ begin(app) }, 200)
                return
            }
            serviceWaits.set(0)
            CallCaptureAccessibilityService.openSettings(app)
            return
        }
        serviceWaits.set(0)
        // Сначала окно службы: настройки прячут обычные панели «поверх других приложений».
        PairingCurtain.show(service)
        startSearch(app)
        AdbSession.openWirelessDebugging(app)
    }

    fun submitCode(context: Context, code: String, onDone: (Boolean, String) -> Unit) {
        val app = context.applicationContext
        if (code.length != 6 || code.any { !it.isDigit() }) {
            onDone(false, "Нужны 6 цифр")
            return
        }
        Thread {
            val result = try {
                val (host, port) = awaitEndpoint(20_000)
                AdbSession.pair(app, host, port, code)
                val detail = if (AdbSession.isLocalhost()) {
                    "Можно вернуться в приложение"
                } else {
                    app.getString(R.string.adb_need_usb)
                }
                true to detail
            } catch (e: Throwable) {
                Log.e(TAG, "Pairing failed", e)
                false to (e.message ?: app.getString(R.string.pairing_failed))
            }
            main.post { onDone(result.first, result.second) }
        }.start()
    }

    fun awaitEndpoint(timeoutMs: Long): Pair<String, Int> {
        val latch = ready.get()
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Не дождались окна с кодом. Откройте его и введите код ещё раз.")
        }
        return endpoint.get()
            ?: throw IllegalStateException("Окно с кодом закрылось раньше, чем код был введён. Откройте его заново.")
    }

    private fun startSearch(app: Context) {
        val latch = CountDownLatch(1)
        endpoint.set(null)
        ready.set(latch)
        Thread {
            try {
                val found = AdbSession.discoverPairing(app, 90_000)
                endpoint.set(found)
                Log.i(TAG, "Pairing endpoint ${found.first}:${found.second}")
            } catch (e: Exception) {
                Log.w(TAG, "Pairing endpoint was not discovered", e)
            } finally {
                latch.countDown()
            }
        }.start()
    }
}
