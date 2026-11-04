package com.example.kwspotter

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Привязка к беспроводной отладке на этом же телефоне и запуск [ShellAudioCapture].
 * Код и порт привязки видны в системном окне «Привязать устройство с помощью кода».
 * Порт самой сессии другой, его библиотека находит сама.
 */
object AdbSession {

    private const val TAG = "AdbSession"
    private const val LOCAL_PORT = 5555
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val SHELL_HEADER = 5
    private const val SHELL_STDIN = 0
    private const val SHELL_STDOUT = 1
    private const val SHELL_STDERR = 2
    private const val SHELL_EXIT = 3

    fun openWirelessDebugging(context: Context) {
        val pages = listOf(
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$WirelessDebuggingActivity"
            ),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        )
        for (intent in pages) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    fun pair(context: Context, host: String, port: Int, code: String) {
        if (code.trim().length != 6) {
            throw IllegalArgumentException("Код должен состоять из 6 цифр")
        }
        val manager = KeywordAdbManager.getInstance(context)
        manager.setTimeout(20, TimeUnit.SECONDS)
        val paired = withMulticast(context) {
            manager.pair(host, port, code.trim())
        }
        if (!paired) throw IOException("Демон отклонил привязку")
        KeywordAdbManager.setPaired(context, true)
        // Пока Wi‑Fi ещё есть, переводим демон на локальный порт. Дальше он
        // слушает 127.0.0.1 и не зависит от сети.
        ensureConnected(context)
    }

    fun isLocalhost(): Boolean = localhost

    /**
     * Связь для захвата звука.
     * Сначала локальный порт: он переживает выключение Wi‑Fi до перезагрузки.
     * После перезагрузки система гасит беспроводную отладку. Сертификат уже
     * запомнен, поэтому приложение включает её само и снова открывает локальный порт.
     */
    fun ensureConnected(context: Context) {
        val manager = KeywordAdbManager.getInstance(context)
        manager.setThrowOnUnauthorised(true)
        if (manager.isConnected()) {
            grantSessionPrivileges(context, manager)
            return
        }
        if (connectLocal(manager)) {
            grantSessionPrivileges(context, manager)
            return
        }
        connectWireless(context, manager)
        switchToLocalhost(context, manager)
        if (!manager.isConnected()) {
            throw IOException(context.getString(R.string.adb_need_wifi))
        }
        grantSessionPrivileges(context, manager)
    }

    /**
     * Подключение сохранённым ключом. Шестизначный код нужен только при первой привязке.
     */
    private fun connectWireless(context: Context, manager: KeywordAdbManager) {
        if (!wifiOn(context)) {
            enableWirelessDebugging(context)
            throw IOException(context.getString(R.string.adb_need_wifi))
        }
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            if (attempt > 0 && !canWriteSecure(context)) break
            val justEnabled = enableWirelessDebugging(context)
            if (justEnabled || attempt > 0) {
                try {
                    Thread.sleep(if (justEnabled) 1_500 else 2_000)
                } catch (_: InterruptedException) {
                    throw IOException(context.getString(R.string.adb_need_wifi))
                }
            }
            manager.setTimeout(12, TimeUnit.SECONDS)
            try {
                withMulticast(context) {
                    manager.connectTls(context, 8_000)
                }
                if (manager.isConnected()) return
            } catch (e: InterruptedException) {
                throw IOException(context.getString(R.string.adb_need_wifi), e)
            } catch (e: Exception) {
                lastError = e
                Log.i(TAG, "Wireless adb is not up yet", e)
            }
        }
        throw IOException(context.getString(R.string.adb_need_wifi), lastError)
    }

    /** @return true, если отладку только что включили и демону нужно время подняться. */
    private fun enableWirelessDebugging(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!canWriteSecure(context)) return false
        return try {
            val resolver = context.contentResolver
            if (Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1) return false
            Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 1)
            Log.i(TAG, "Wireless debugging enabled with the saved key")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not enable wireless debugging", e)
            false
        }
    }

    /** Обрывает текущий звонок. Сначала системный вызов, затем клавиша «завершить» из оболочки. */
    fun endCall(context: Context) {
        val app = context.applicationContext
        if (endViaTelecom(app)) Log.i(TAG, "Call ended")
        Thread {
            try {
                val manager = KeywordAdbManager.getInstance(app)
                if (!manager.isConnected()) return@Thread
                manager.openStream("shell:input keyevent KEYCODE_ENDCALL").close()
                Log.i(TAG, "End-call key sent")
            } catch (e: Exception) {
                Log.e(TAG, "Could not end the call", e)
            }
        }.start()
    }

    /**
     * Запускает [ShellAudioCapture] и отдаёт его стандартный вывод.
     * Обычный терминал отладки на этом телефоне печатает приглашение `a56x:/ $`
     * и превращает перевод строки в `\r\n`, из-за чего маркер PCM не находится.
     * `shell,v2,raw` — поток байтов без терминала.
     */
    fun openCapture(context: Context): CaptureLink {
        ensureConnected(context)
        val manager = KeywordAdbManager.getInstance(context)
        manager.setTimeout(15, TimeUnit.SECONDS)

        // Короткая команда умещается в лимит библиотеки. Гасит прошлый захват, если он завис.
        try {
            manager.openStream("shell:pkill -f com.example.kwspotter.ShellAudioCapture").close()
        } catch (_: Exception) {
        }

        val stream = try {
            manager.openStream("shell,v2,raw:/system/bin/sh")
        } catch (e: Exception) {
            Log.w(TAG, "Raw shell is unavailable", e)
            throw IOException("Беспроводная отладка не открыла канал захвата", e)
        }
        val stdout = ShellStdoutStream(stream.openInputStream())
        val script = launchScript.toByteArray(Charsets.UTF_8)
        stream.openOutputStream().apply {
            writeShellPacket(this, SHELL_STDIN, script)
            flush()
        }
        return CaptureLink(stream, stdout)
    }

    class CaptureLink(
        private val stream: AdbStream,
        val input: InputStream
    ) : Closeable {
        fun stderr(): String = (input as? ShellStdoutStream)?.stderrText().orEmpty()

        override fun close() {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun writeShellPacket(out: OutputStream, id: Int, data: ByteArray) {
        val header = ByteArray(SHELL_HEADER)
        header[0] = id.toByte()
        val length = data.size
        header[1] = (length and 0xff).toByte()
        header[2] = (length shr 8 and 0xff).toByte()
        header[3] = (length shr 16 and 0xff).toByte()
        header[4] = (length shr 24 and 0xff).toByte()
        out.write(header)
        if (data.isNotEmpty()) out.write(data)
    }

    fun disconnect(context: Context) {
        try {
            KeywordAdbManager.getInstance(context).disconnect()
        } catch (_: Exception) {
        }
    }

    @Volatile
    private var localhost = false

    @Volatile
    private var endCallGranted = false

    @Volatile
    private var debuggingRestoreAllowed = false

    private fun grantSessionPrivileges(context: Context, manager: KeywordAdbManager) {
        grantEndCall(context, manager)
        allowDebuggingRestore(context, manager)
    }

    private fun canWriteSecure(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Разрешение переживает перезагрузку. После неё приложение само пишет
     * adb_wifi_enabled и подключается уже известным сертификатом.
     */
    private fun allowDebuggingRestore(context: Context, manager: KeywordAdbManager) {
        if (debuggingRestoreAllowed || canWriteSecure(context)) {
            debuggingRestoreAllowed = true
            return
        }
        try {
            manager.openStream(
                "shell:pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            ).close()
            debuggingRestoreAllowed = canWriteSecure(context)
            if (debuggingRestoreAllowed) {
                Log.i(TAG, "Saved key will restore wireless debugging after reboot")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not allow debugging to survive reboot", e)
        }
    }

    private fun grantEndCall(context: Context, manager: KeywordAdbManager) {
        if (endCallGranted) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            endCallGranted = true
            return
        }
        try {
            manager.openStream(
                "shell:pm grant ${context.packageName} android.permission.ANSWER_PHONE_CALLS"
            ).close()
            endCallGranted = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not grant call control", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun endViaTelecom(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        return try {
            telecom.endCall()
        } catch (e: Exception) {
            Log.w(TAG, "Telecom did not end the call", e)
            false
        }
    }

    private fun connectLocal(manager: KeywordAdbManager): Boolean {
        manager.setTimeout(3, TimeUnit.SECONDS)
        return try {
            val connected = manager.connect("127.0.0.1", LOCAL_PORT)
            val ready = connected || manager.isConnected()
            if (ready) {
                localhost = true
                Log.i(TAG, "Using local adb 127.0.0.1:$LOCAL_PORT")
                return true
            }
            try {
                manager.disconnect()
            } catch (_: Exception) {
            }
            localhost = false
            false
        } catch (e: Exception) {
            Log.i(TAG, "Local adb is not listening", e)
            try {
                manager.disconnect()
            } catch (_: Exception) {
            }
            localhost = false
            false
        }
    }

    /**
     * `tcpip:5555` перезапускает демон отладки на локальном порту.
     * Это работает, когда включена отладка по USB, и не требует Wi‑Fi.
     * Перезагрузка снимает порт. Приложение включает беспроводную отладку
     * сохранённым ключом и вызывает эту команду снова.
     */
    private fun switchToLocalhost(context: Context, manager: KeywordAdbManager) {
        if (!usbDebuggingOn(context)) {
            Log.i(TAG, "USB debugging is off, wireless adb stays tied to Wi-Fi")
            localhost = false
            return
        }
        var accepted: Boolean
        try {
            val stream = manager.openStream("tcpip:$LOCAL_PORT")
            val reply = readBriefly(stream)
            Log.i(TAG, "tcpip reply: $reply")
            accepted = !reply.contains("error", ignoreCase = true)
            try {
                stream.close()
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            accepted = message.contains("closed", ignoreCase = true) ||
                message.contains("reset", ignoreCase = true) ||
                message.contains("broken pipe", ignoreCase = true) ||
                message.contains("abort", ignoreCase = true)
            Log.i(TAG, "tcpip ended the session", e)
        }
        if (!accepted && manager.isConnected()) return
        try {
            manager.disconnect()
        } catch (_: Exception) {
        }
        if (waitForLocal(manager)) return
        if (!wifiOn(context)) return
        try {
            manager.setTimeout(12, TimeUnit.SECONDS)
            withMulticast(context) {
                manager.connectTls(context, 8_000)
            }
            localhost = false
        } catch (e: Exception) {
            Log.w(TAG, "Wireless adb did not return", e)
        }
    }

    private fun waitForLocal(manager: KeywordAdbManager): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 6_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (connectLocal(manager)) return true
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun readBriefly(stream: AdbStream): String {
        val input = stream.openInputStream()
        val buffer = ByteArray(256)
        val task = java.util.concurrent.FutureTask {
            try {
                val read = input.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
        Thread(task, "adb-tcpip").start()
        return try {
            task.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            task.cancel(true)
            ""
        }
    }

    private fun usbDebuggingOn(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Exception) {
            true
        }
    }

    private fun wifiOn(context: Context): Boolean {
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        @Suppress("DEPRECATION")
        val networks = connectivity.allNetworks
        return networks.any { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun <T> withMulticast(context: Context, block: () -> T): T {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("kwspotter-adb")
        lock?.acquire()
        try {
            return block()
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }

    /**
     * Ждёт, пока на экране открыто окно привязки: только тогда система объявляет порт.
     * Нужен, если пользователь не вписал порт руками.
     */
    /** Хост и порт окна «Привязать устройство с помощью кода». Окно должно быть открыто. */
    fun discoverPairing(context: Context, timeoutMs: Long = 20_000): Pair<String, Int> {
        val host = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val port = AtomicInteger(-1)
        val found = CountDownLatch(1)
        val discovery = AdbMdns(
            context.applicationContext,
            AdbMdns.SERVICE_TYPE_TLS_PAIRING
        ) { address, discoveredPort ->
            val addressText = address?.hostAddress
            if (!addressText.isNullOrBlank() && discoveredPort > 0) {
                host.set(addressText)
                port.set(discoveredPort)
                found.countDown()
            }
        }
        withMulticast(context) {
            discovery.start()
            try {
                found.await(timeoutMs, TimeUnit.MILLISECONDS)
            } finally {
                discovery.stop()
            }
        }
        val foundHost = host.get()
        val foundPort = port.get()
        if (foundHost == null || foundPort <= 0) {
            throw IOException("Окно с кодом не найдено. Откройте «Привязать устройство с помощью кода» и не закрывайте его.")
        }
        return foundHost to foundPort
    }

    /**
     * Разбирает пакеты shell v2 и отдаёт только стандартный вывод.
     * Сообщения об ошибках оболочки остаются в стороне и не портят PCM.
     */
    private class ShellStdoutStream(private val raw: InputStream) : InputStream() {
        private val stderr = ByteArrayOutputStream()
        private var packet = ByteArray(0)
        private var packetPos = 0

        fun stderrText(): String = stderr.toString(Charsets.UTF_8).trim()

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (packetPos >= packet.size) {
                if (!readPacket()) return -1
            }
            val count = minOf(length, packet.size - packetPos)
            System.arraycopy(packet, packetPos, buffer, offset, count)
            packetPos += count
            return count
        }

        private fun readPacket(): Boolean {
            val header = readFully(SHELL_HEADER) ?: return false
            val id = header[0].toInt() and 0xff
            val size = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (size < 0 || size > 1024 * 1024) {
                throw IOException("Канал захвата ответил не тем протоколом")
            }
            val body = if (size == 0) ByteArray(0) else readFully(size)
                ?: throw IOException("Канал захвата оборвался")
            when (id) {
                SHELL_STDOUT -> {
                    packet = body
                    packetPos = 0
                }
                SHELL_STDERR -> stderr.write(body)
                SHELL_EXIT -> Unit
                else -> Log.w(TAG, "Ignoring shell packet $id")
            }
            return true
        }

        private fun readFully(size: Int): ByteArray? {
            val buffer = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = raw.read(buffer, offset, size - offset)
                if (read < 0) return if (offset == 0) null else throw IOException("Канал захвата оборвался")
                offset += read
            }
            return buffer
        }
    }

    // Скрипт уходит в уже открытую оболочку, поэтому может быть длинным.
    // Библиотека ломается, только если длинная команда передана в имени канала.
    // Ошибка — это отдельная строка, которая начинается с KWSERR:.
    private val launchScript = """
        CP=`pm path com.example.kwspotter | head -n 1 | cut -d: -f2-`
        if [ -z "${'$'}CP" ]; then echo 'KWSERR:Приложение не найдено'; exit 1; fi
        if [ ! -r "${'$'}CP" ]; then cp "${'$'}CP" /data/local/tmp/kwspotter.apk || { echo 'KWSERR:Не удалось прочитать пакет'; exit 1; }; CP=/data/local/tmp/kwspotter.apk; fi
        export CLASSPATH="${'$'}CP"
        app_process /system/bin com.example.kwspotter.ShellAudioCapture 2>/data/local/tmp/kwspotter-shell.log
        echo "KWSERR:`tail -n 1 /data/local/tmp/kwspotter-shell.log 2>/dev/null`"
        exit 1

    """.trimIndent() + "\n"
}
