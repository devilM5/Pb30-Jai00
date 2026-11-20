package com.example.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

interface LlamaInferenceEngine {
    fun isNativeLibraryAvailable(): Boolean
    fun getLoadedModelPath(): String?
    fun getLoadedContextSize(): Int
    fun getModelArchitecture(): String
    suspend fun loadModel(modelPath: String, contextSize: Int, threads: Int): Result<String>
    fun unloadModel()
    fun streamCompletion(
        formattedPrompt: String,
        maxTokens: Int,
        temperature: Float,
        stopSequences: List<String> = DhirPromptFormatter.STOP_SEQUENCES
    ): Flow<String>
    fun abortGeneration()
}

class AndroidLlamaNativeBridge : LlamaInferenceEngine {

    interface TokenCallback {
        fun onToken(piece: String): Boolean // return false to stop early
    }

    @Volatile
    private var loadedModelPath: String? = null

    @Volatile
    private var loadedContextSize: Int = 0

    @Volatile
    private var loadedArchitecture: String = ""

    @Volatile
    private var abortRequested = false

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val inferenceDispatcher = Dispatchers.IO.limitedParallelism(1)

    override fun isNativeLibraryAvailable(): Boolean = nativeLibraryLoaded

    override fun getLoadedModelPath(): String? = loadedModelPath

    override fun getLoadedContextSize(): Int = loadedContextSize

    override fun getModelArchitecture(): String = loadedArchitecture

    override suspend fun loadModel(
        modelPath: String,
        contextSize: Int,
        threads: Int
    ): Result<String> = withContext(inferenceDispatcher) {
        if (!nativeLibraryLoaded) {
            return@withContext Result.failure(
                IllegalStateException("Native llama.cpp library (libfriday_llama.so) is not loaded on this runtime.")
            )
        }
        val file = File(modelPath)
        val validation = DhirModelManager.validateGgufFile(file)
        if (!validation.isValid) {
            return@withContext Result.failure(
                IllegalStateException(validation.errorReason ?: "Invalid GGUF file at $modelPath")
            )
        }
        if (loadedModelPath == modelPath && loadedContextSize == contextSize) {
            return@withContext Result.success(loadedArchitecture.ifBlank { "dhir" })
        }

        if (loadedModelPath != null) {
            runCatching { nativeUnloadModel() }
            loadedModelPath = null
        }

        val safeThreads = threads.coerceIn(1, 8)
        val status = runCatching {
            nativeLoadModel(modelPath, contextSize.coerceIn(512, 8192), safeThreads)
        }.getOrElse { err ->
            Log.e(TAG, "JNI loadModel threw exception", err)
            return@withContext Result.failure(err)
        }

        if (status != 0) {
            val reason = when (status) {
                -1 -> "Failed to open GGUF model file (check architecture/quantization compatibility)."
                -2 -> "Failed to allocate llama context (insufficient free RAM for ctx=$contextSize)."
                else -> "Native llama_load failed with code $status."
            }
            return@withContext Result.failure(IllegalStateException(reason))
        }

        val desc = runCatching { nativeGetModelDesc() }.getOrDefault("dhir")
        loadedModelPath = modelPath
        loadedContextSize = contextSize
        loadedArchitecture = desc.ifBlank { "dhir" }
        Result.success(loadedArchitecture)
    }

    override fun unloadModel() {
        abortRequested = true
        if (nativeLibraryLoaded && loadedModelPath != null) {
            runCatching { nativeAbort() }
            runCatching { nativeUnloadModel() }
        }
        loadedModelPath = null
        loadedContextSize = 0
        loadedArchitecture = ""
    }

    override fun streamCompletion(
        formattedPrompt: String,
        maxTokens: Int,
        temperature: Float,
        stopSequences: List<String>
    ): Flow<String> = callbackFlow {
        if (!nativeLibraryLoaded) {
            close(IllegalStateException("Native libfriday_llama.so unavailable in current environment."))
            return@callbackFlow
        }
        if (loadedModelPath == null) {
            close(IllegalStateException("Dhīr 2B model is not loaded in memory."))
            return@callbackFlow
        }

        abortRequested = false
        val buffer = StringBuilder()

        withContext(inferenceDispatcher) {
            try {
                val callback = object : TokenCallback {
                    override fun onToken(piece: String): Boolean {
                        if (abortRequested) return false
                        buffer.append(piece)
                        val current = buffer.toString()
                        val hitStop = stopSequences.firstOrNull { stop -> current.contains(stop) }
                        if (hitStop != null) {
                            val trimmed = current.substringBefore(hitStop)
                            trySend(trimmed)
                            return false
                        }
                        trySend(current)
                        return true
                    }
                }
                val rc = nativeGenerate(
                    prompt = formattedPrompt,
                    maxTokens = maxTokens.coerceIn(32, 2048),
                    temperature = temperature.coerceIn(0f, 1.5f),
                    callback = callback
                )
                if (rc < 0 && !abortRequested) {
                    close(IllegalStateException("Dhīr native inference failed (code $rc)."))
                    return@withContext
                }
                close()
            } catch (c: CancellationException) {
                runCatching { nativeAbort() }
                close(c)
            } catch (t: Throwable) {
                close(t)
            }
        }

        awaitClose {
            abortGeneration()
        }
    }

    override fun abortGeneration() {
        abortRequested = true
        if (nativeLibraryLoaded) {
            runCatching { nativeAbort() }
        }
    }

    private external fun nativeLoadModel(modelPath: String, contextSize: Int, threads: Int): Int
    private external fun nativeGetModelDesc(): String
    private external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        callback: TokenCallback
    ): Int
    private external fun nativeAbort()
    private external fun nativeUnloadModel()

    companion object {
        private const val TAG = "AndroidLlamaBridge"

        @Volatile
        var nativeLibraryLoaded: Boolean = false
            private set

        init {
            try {
                System.loadLibrary("friday_llama")
                nativeLibraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libfriday_llama.so not loaded (expected on JVM unit tests): ${e.message}")
                nativeLibraryLoaded = false
            } catch (t: Throwable) {
                Log.w(TAG, "Native library init skipped: ${t.message}")
                nativeLibraryLoaded = false
            }
        }
    }
}
