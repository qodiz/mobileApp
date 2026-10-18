package com.example.kwspotter

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Постоянно висит в фоне. На любом звонке сам открывает захват голоса собеседника.
 * Закрытие экрана приложение не останавливает: служба снова поднимается.
 */
class ListenerService : Service() {

    enum class State { STOPPED, WATCHING, LOADING_MODEL, LISTENING, ERROR }

    companion object {
        private const val TAG = "ListenerService"
        private const val SAMPLE_RATE = 16000.0f
        private val PCM_MAGIC = "KWSPCM1\n".toByteArray(Charsets.US_ASCII)
        private const val MAX_RETRIES = 3

        private val GRAMMAR = listOf(
            "фсб",
            "эф эс бэ",
            "эфэсбэ",
            "эфсб",
            "ефсб",
            "эфэсб",
            "фэсбэ",
            "эфесбе",
            "афсб",
            "полиция",
            "полиции",
            "полицию",
            "полицией",
            "полицейский",
            "полицейская",
            "полицейские",
            "палиция",
            "[unk]"
        )

        private val _state = MutableStateFlow(State.STOPPED)
        val state = _state.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError = _lastError.asStateFlow()

        fun isReady(context: Context): Boolean {
            if (!KeywordAdbManager.isPaired(context)) return false
            if (!Settings.canDrawOverlays(context)) return false
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return true
        }

        fun start(context: Context) {
            val app = context.applicationContext
            if (!isReady(app)) return
            try {
                ContextCompat.startForegroundService(app, Intent(app, ListenerService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Listener did not start", e)
            }
        }
    }

    private val audioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(SupervisorJob() + audioDispatcher)
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var captureLink: AdbSession.CaptureLink? = null
    private var captureJob: Job? = null
    private var lastAlertAt = 0L
    private var curtainSequence = 0
    private val endingCall = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retries = 0
    private var armed = false
    private val capturing = AtomicBoolean(false)

    private var telephony: TelephonyManager? = null
    private var modernCallback: TelephonyCallback? = null

    @Suppress("DEPRECATION")
    private var legacyListener: PhoneStateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.createChannels(this)
        enterForeground(listening = false)
        if (!armed) {
            armed = true
            registerCalls()
            watchWifi()
            serviceScope.launch {
                restoreAdb()
                try {
                    warmUp()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Model did not load", e)
                    _lastError.value = e.message ?: "Модель не загрузилась"
                    _state.value = State.ERROR
                    updateNotification(listening = false)
                }
                if (isOffHook()) beginCapture()
            }
        } else if (isOffHook()) {
            beginCapture()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestart()
        start(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        unregisterCalls()
        unregisterWifi()
        closeCaptureLink()
        captureJob?.cancel()
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
        serviceScope.cancel()
        audioDispatcher.close()
        scheduleRestart()
        _state.value = State.STOPPED
        super.onDestroy()
    }

    private fun enterForeground(listening: Boolean) {
        val notification = NotificationHelper.buildServiceNotification(this, notificationText(listening))
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(
            this,
            NotificationHelper.SERVICE_NOTIFICATION_ID,
            notification,
            serviceType
        )
    }

    private fun updateNotification(listening: Boolean) {
        val notification = NotificationHelper.buildServiceNotification(this, notificationText(listening))
        androidx.core.app.NotificationManagerCompat.from(this)
            .notify(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
    }

    private fun notificationText(listening: Boolean): String {
        if (listening) return getString(R.string.service_notification_listening)
        val error = _lastError.value
        if (_state.value == State.ERROR && !error.isNullOrBlank()) return error.take(120)
        return getString(R.string.service_notification_text)
    }

    private fun registerCalls() {
        val manager = getSystemService(TelephonyManager::class.java) ?: return
        telephony = manager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleCallState(state)
            }
            modernCallback = callback
            manager.registerTelephonyCallback(mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state)
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun watchWifi() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        if (networkCallback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                serviceScope.launch { restoreAdb() }
            }
        }
        connectivity.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private fun unregisterWifi() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    /** Локальный порт отладки поднимается сам, как только телефон видит Wi‑Fi. */
    private fun restoreAdb() {
        if (!KeywordAdbManager.isPaired(applicationContext)) return
        try {
            AdbSession.ensureConnected(applicationContext)
            if (_state.value != State.ERROR) _lastError.value = null
            if (recognizer != null && !isOffHook() && _state.value != State.LISTENING) {
                _state.value = State.WATCHING
                _lastError.value = null
            }
            updateNotification(listening = _state.value == State.LISTENING)
        } catch (e: Exception) {
            Log.w(TAG, "Adb is not reachable", e)
            _lastError.value = e.message
            updateNotification(listening = false)
        }
    }

    private fun unregisterCalls() {
        val manager = telephony ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernCallback?.let { manager.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let { manager.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
        modernCallback = null
        legacyListener = null
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> beginCapture()
            TelephonyManager.CALL_STATE_IDLE -> endCapture()
        }
    }

    @Suppress("DEPRECATION")
    private fun isOffHook(): Boolean {
        return try {
            telephony?.callState == TelephonyManager.CALL_STATE_OFFHOOK
        } catch (_: SecurityException) {
            false
        }
    }

    private suspend fun warmUp() {
        if (recognizer != null) return
        _state.value = State.LOADING_MODEL
        var error: String? = null
        ModelManager.ensureModel(applicationContext) { progress ->
            if (progress is ModelManager.Progress.Error) error = progress.message
        }
        if (error != null) throw IllegalStateException(error)
        val loadedModel = Model(ModelManager.modelDir(applicationContext).absolutePath)
        val loadedRecognizer = Recognizer(loadedModel, SAMPLE_RATE, JSONArray(GRAMMAR).toString())
        model = loadedModel
        recognizer = loadedRecognizer
        _state.value = State.WATCHING
        _lastError.value = null
    }

    private fun beginCapture() {
        if (!capturing.compareAndSet(false, true)) return
        captureJob = serviceScope.launch {
            try {
                warmUp()
                if (isOffHook()) runCapture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
                rememberFailure(e)
            } finally {
                closeCaptureLink()
                capturing.set(false)
                if (isActive && isOffHook() && _state.value != State.ERROR) {
                    beginCapture()
                } else if (recognizer != null && _state.value != State.ERROR) {
                    _state.value = State.WATCHING
                    updateNotification(listening = false)
                }
            }
        }
    }

    private suspend fun runCapture() {
        val activeRecognizer = recognizer ?: return
        activeRecognizer.reset()
        lastAlertAt = 0L
        endingCall.set(false)
        _state.value = State.LOADING_MODEL
        updateNotification(listening = true)
        try {
            val link = AdbSession.openCapture(applicationContext)
            captureLink = link
            val input = link.input
            val prelude = readUntilPcm(input)
            if (prelude.isNotEmpty()) feedPcm(activeRecognizer, prelude, prelude.size)
            _state.value = State.LISTENING
            _lastError.value = null
            retries = 0
            val buffer = ByteArray(3200)
            while (currentCoroutineContext().isActive && isOffHook()) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                feedPcm(activeRecognizer, buffer, read)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive || !isOffHook()) return
            Log.e(TAG, "Call capture interrupted", e)
            if (e.message?.contains("pair", ignoreCase = true) == true ||
                e.javaClass.simpleName.contains("Pairing")
            ) {
                KeywordAdbManager.setPaired(applicationContext, false)
            }
            rememberFailure(e)
            if (retries < MAX_RETRIES && isOffHook()) {
                retries += 1
                delay(1_500)
                _state.value = State.WATCHING
            }
        }
    }

    private fun rememberFailure(error: Exception) {
        val stderr = captureLink?.stderr().orEmpty()
        _lastError.value = listOfNotNull(
            error.message,
            stderr.takeIf { it.isNotBlank() }
        ).joinToString("\n").ifBlank { "Не удалось начать захват звонка" }
        _state.value = State.ERROR
        updateNotification(listening = false)
    }

    private fun endCapture() {
        retries = 0
        endingCall.set(false)
        closeCaptureLink()
        captureJob?.cancel()
        if (curtainSequence == 0) AlertCurtain.hide(applicationContext)
        if (recognizer != null) {
            _state.value = State.WATCHING
            updateNotification(listening = false)
        }
    }

    private fun closeCaptureLink() {
        try {
            captureLink?.close()
        } catch (_: Exception) {
        }
        captureLink = null
    }

    private fun scheduleRestart() {
        val restart = PendingIntent.getService(
            applicationContext,
            1,
            Intent(applicationContext, ListenerService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = getSystemService(AlarmManager::class.java) ?: return
        alarm.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1_500,
            restart
        )
    }

    private fun feedPcm(activeRecognizer: Recognizer, buffer: ByteArray, length: Int) {
        val finished = activeRecognizer.acceptWaveForm(buffer, length)
        val hypothesis = if (finished) activeRecognizer.result else activeRecognizer.partialResult
        handleHypothesis(hypothesis, if (finished) "text" else "partial")
    }

    private fun readUntilPcm(input: InputStream): ByteArray {
        val pending = ByteArrayOutputStream()
        while (pending.size() < 16_384) {
            val next = input.read()
            if (next < 0) break
            pending.write(next)
            val bytes = pending.toByteArray()
            val marker = indexOf(bytes, PCM_MAGIC)
            if (marker >= 0) {
                val start = marker + PCM_MAGIC.size
                return if (start < bytes.size) bytes.copyOfRange(start, bytes.size) else ByteArray(0)
            }
            val text = String(bytes, Charsets.ISO_8859_1)
            val error = shellError(text)
            if (error != null) throw IllegalStateException(error)
        }
        val seen = String(pending.toByteArray(), Charsets.ISO_8859_1).take(300)
        throw IllegalStateException("Захват звука не запустился. $seen")
    }

    private fun shellError(text: String): String? {
        val lines = text.split('\n')
        val complete = if (text.endsWith("\n")) lines else lines.dropLast(1)
        for (line in complete) {
            val trimmed = line.trim('\r', ' ', '\t')
            if (trimmed.startsWith("KWSERR:")) {
                return trimmed.removePrefix("KWSERR:").trim().ifBlank { "Захват звука не запустился" }
            }
        }
        return null
    }

    private fun handleHypothesis(hypothesis: String?, key: String) {
        if (hypothesis.isNullOrBlank()) return
        val text = try {
            JSONObject(hypothesis).optString(key, "")
        } catch (_: Exception) {
            ""
        }
        Log.d(TAG, "raw hypothesis ($key): '$text'")
        val keyword = KeywordMatcher.match(text) ?: return
        val now = SystemClock.elapsedRealtime()
        if (endingCall.compareAndSet(false, true)) {
            Log.i(TAG, "Ending call after $keyword: $text")
            showHangupCurtains()
            AdbSession.endCall(applicationContext)
        }
        if (AlertCurtain.isShowing()) {
            lastAlertAt = now
            return
        }
        if (now - lastAlertAt < 8_000) return
        lastAlertAt = now
        Log.i(TAG, "Keyword detected in: $text")
        AlertCurtain.show(applicationContext)
    }

    /** Красная шторка 2 секунды, затем зелёная 3 секунды. */
    private fun showHangupCurtains() {
        val token = ++curtainSequence
        val app = applicationContext
        AlertCurtain.show(app)
        mainHandler.postDelayed(hideRed@{
            if (curtainSequence != token) return@hideRed
            AlertCurtain.hideWarning(app)
            AlertCurtain.showEnded(app)
            mainHandler.postDelayed(hideGreen@{
                if (curtainSequence != token) return@hideGreen
                curtainSequence = 0
                AlertCurtain.hide(app)
            }, 3_000)
        }, 2_000)
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        if (pattern.isEmpty() || data.size < pattern.size) return -1
        outer@ for (start in 0..data.size - pattern.size) {
            for (offset in pattern.indices) {
                if (data[start + offset] != pattern[offset]) continue@outer
            }
            return start
        }
        return -1
    }
}
