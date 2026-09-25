package com.example.kwspotter

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Foreground service that keeps the microphone open and runs fully offline speech
 * recognition via Vosk while the app is "listening". Recognized text never leaves the
 * device: it is only inspected in-process by [KeywordMatcher] to detect the keyword.
 */
class ListenerService : Service(), RecognitionListener {

    enum class State { STOPPED, LOADING_MODEL, LISTENING, ERROR }

    companion object {
        private const val TAG = "ListenerService"
        private const val SAMPLE_RATE = 16000.0f

        const val ACTION_START = "com.example.kwspotter.action.START"
        const val ACTION_STOP = "com.example.kwspotter.action.STOP"

        // Restricted grammar: the recognizer only decodes these phrases (plus [unk] for
        // anything else), which is faster and more accurate than open vocabulary decoding
        // for a single-keyword spotting task.
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
            "[unk]"
        )

        private val _state = MutableStateFlow(State.STOPPED)
        val state = _state.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError = _lastError.asStateFlow()
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopSelf()
            }
            else -> startListeningPipeline()
        }
        return START_NOT_STICKY
    }

    private fun startListeningPipeline() {
        if (_state.value == State.LOADING_MODEL || _state.value == State.LISTENING) return

        val notification = NotificationHelper.buildServiceNotification(this)
        ServiceCompat.startForeground(
            this,
            NotificationHelper.SERVICE_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        _state.value = State.LOADING_MODEL
        _lastError.value = null

        serviceScope.launch {
            try {
                val modelDir = ModelManager.modelDir(applicationContext)
                if (!ModelManager.isModelReady(applicationContext)) {
                    throw IllegalStateException("Offline model is not downloaded yet")
                }

                val loadedModel = Model(modelDir.absolutePath)
                val grammarJson = org.json.JSONArray(GRAMMAR).toString()
                val loadedRecognizer = Recognizer(loadedModel, SAMPLE_RATE, grammarJson)

                model = loadedModel
                recognizer = loadedRecognizer

                val service = SpeechService(loadedRecognizer, SAMPLE_RATE)
                speechService = service
                service.startListening(this@ListenerService)

                _state.value = State.LISTENING
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recognition", e)
                _lastError.value = e.message ?: "Unknown error"
                _state.value = State.ERROR
                stopListening()
                stopSelf()
            }
        }
    }

    private fun stopListening() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null

        recognizer?.close()
        recognizer = null

        model?.close()
        model = null

        _state.value = State.STOPPED
        ServiceCompat.stopForeground(this, Service.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopListening()
        serviceScope.cancel()
        super.onDestroy()
    }

    // --- org.vosk.android.RecognitionListener ---

    override fun onPartialResult(hypothesis: String?) {
        handleHypothesis(hypothesis, key = "partial")
    }

    override fun onResult(hypothesis: String?) {
        handleHypothesis(hypothesis, key = "text")
    }

    override fun onFinalResult(hypothesis: String?) {
        handleHypothesis(hypothesis, key = "text")
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Recognition error", exception)
        _lastError.value = exception?.message ?: "Recognition error"
    }

    override fun onTimeout() {
        // No speech detected for a while; keep listening.
    }

    private fun handleHypothesis(hypothesis: String?, key: String) {
        if (hypothesis.isNullOrBlank()) return
        val text = try {
            JSONObject(hypothesis).optString(key, "")
        } catch (e: Exception) {
            ""
        }
        if (text.isNotBlank() && KeywordMatcher.containsKeyword(text)) {
            Log.i(TAG, "Keyword detected in: $text")
            NotificationHelper.showKeywordDetected(applicationContext)
        }
    }
}
