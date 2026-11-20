package com.example.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

data class VoiceUiState(
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val speakingMessageId: String? = null,
    val partialTranscript: String = "",
    val ttsReady: Boolean = false,
    val hindiTtsAvailable: Boolean = false,
    val speechRecognitionAvailable: Boolean = true,
    val statusMessage: String? = null
)

class VoiceController(context: Context) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(
        VoiceUiState(
            speechRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
        )
    )
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var pendingLocaleTag: String = "hi-IN"

    init {
        initTts()
    }

    private fun initTts() {
        try {
            textToSpeech = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val tts = textToSpeech
                    val hindiLocale = Locale("hi", "IN")
                    val hiResult = tts?.isLanguageAvailable(hindiLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    val hindiSupported = hiResult >= TextToSpeech.LANG_AVAILABLE
                    if (hindiSupported) {
                        tts?.language = hindiLocale
                    } else {
                        tts?.language = Locale("en", "IN")
                    }
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _state.value = _state.value.copy(
                                isSpeaking = true,
                                speakingMessageId = utteranceId
                            )
                        }

                        override fun onDone(utteranceId: String?) {
                            _state.value = _state.value.copy(
                                isSpeaking = false,
                                speakingMessageId = null
                            )
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _state.value = _state.value.copy(
                                isSpeaking = false,
                                speakingMessageId = null
                            )
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            _state.value = _state.value.copy(
                                isSpeaking = false,
                                speakingMessageId = null
                            )
                        }
                    })
                    _state.value = _state.value.copy(
                        ttsReady = true,
                        hindiTtsAvailable = hindiSupported
                    )
                } else {
                    Log.w(TAG, "TextToSpeech init failed with status=$status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TextToSpeech", e)
        }
    }

    fun startListening(
        languageTag: String = "hi-IN",
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            val msg = "Speech recognition service is not available on this device."
            _state.value = _state.value.copy(
                speechRecognitionAvailable = false,
                statusMessage = msg
            )
            onError(msg)
            return
        }

        stopSpeaking()
        pendingLocaleTag = languageTag

        try {
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _state.value = _state.value.copy(
                        isListening = true,
                        partialTranscript = "",
                        statusMessage = if (languageTag.startsWith("hi")) {
                            "Sun raha hoon… Hindi / Hinglish mein bolein"
                        } else {
                            "Listening…"
                        }
                    )
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    _state.value = _state.value.copy(isListening = false)
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network unavailable for speech recognition."
                        SpeechRecognizer.ERROR_NO_MATCH -> "Kuch samajh nahi aaya. Please speak clearly."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
                        else -> "Voice input error ($error)."
                    }
                    _state.value = _state.value.copy(
                        isListening = false,
                        partialTranscript = "",
                        statusMessage = errorMsg
                    )
                    onError(errorMsg)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull().orEmpty().trim()
                    _state.value = _state.value.copy(
                        isListening = false,
                        partialTranscript = "",
                        statusMessage = null
                    )
                    if (spoken.isNotEmpty()) {
                        onResult(spoken)
                    } else {
                        onError("No speech recognized.")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partial.isNotEmpty()) {
                        _state.value = _state.value.copy(partialTranscript = partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpeechRecognizer", e)
            _state.value = _state.value.copy(
                isListening = false,
                statusMessage = e.localizedMessage
            )
            onError(e.localizedMessage ?: "Failed to start voice input.")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }
        _state.value = _state.value.copy(isListening = false)
    }

    fun speak(text: String, messageId: String = UUID.randomUUID().toString(), preferHindi: Boolean = true) {
        val tts = textToSpeech ?: return
        if (!_state.value.ttsReady) {
            _state.value = _state.value.copy(statusMessage = "Voice engine is initializing…")
            return
        }
        val cleanText = text
            .replace(Regex("```[\\s\\S]*?```"), " Code snippet displayed on screen. ")
            .replace(Regex("[*#`>]"), "")
            .trim()
            .take(3500)

        if (cleanText.isEmpty()) return

        val targetLocale = if (preferHindi && _state.value.hindiTtsAvailable) {
            Locale("hi", "IN")
        } else {
            Locale("en", "IN")
        }
        tts.language = targetLocale
        tts.setSpeechRate(1.0f)

        val params = Bundle()
        tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, messageId)
    }

    fun stopSpeaking() {
        try {
            textToSpeech?.stop()
        } catch (_: Exception) {
        }
        _state.value = _state.value.copy(isSpeaking = false, speakingMessageId = null)
    }

    fun clearStatus() {
        _state.value = _state.value.copy(statusMessage = null)
    }

    fun release() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {
        }
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "VoiceController"
    }
}
