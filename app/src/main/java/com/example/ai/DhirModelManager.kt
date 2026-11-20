package com.example.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.model.DhirModelState
import com.example.data.model.DhirModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

data class GgufValidationResult(
    val isValid: Boolean,
    val fileSizeBytes: Long,
    val ggufVersion: Int = 0,
    val errorReason: String? = null
)

/**
 * Manages safe on-device storage, SAF import, verified HTTPS download, RAM budgeting,
 * and GGUF validation for `dhir-2b-Q4_K_M.gguf` without renaming or modifying user model bytes.
 */
class DhirModelManager(
    private val context: Context,
    private val nativeReadyProvider: () -> Boolean = { false }
) {
    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _state = MutableStateFlow(inspectModelState())
    val state: StateFlow<DhirModelState> = _state.asStateFlow()

    @Volatile
    private var downloadCancelled = false

    fun refreshState(): DhirModelState {
        val inspected = inspectModelState()
        _state.value = inspected
        return inspected
    }

    fun inspectModelState(loadedFilePath: String? = null, effectiveCtx: Int = 0, arch: String = ""): DhirModelState {
        val (totalMb, availMb, lowMem) = readDeviceMemoryInfo(context)
        val nativeReady = nativeReadyProvider()
        val existingFile = findInstalledDhirFile()

        if (existingFile == null) {
            return DhirModelState(
                status = DhirModelStatus.NOT_INSTALLED,
                expectedFilename = EXPECTED_FILENAME_LOWER,
                totalDeviceRamMb = totalMb,
                availableDeviceRamMb = availMb,
                nativeBackendReady = nativeReady,
                statusDetail = "Place or import dhir-2b-Q4_K_M.gguf (~1.29 GB) via File Picker or Official Download."
            )
        }

        val validation = validateGgufFile(existingFile)
        if (!validation.isValid) {
            return DhirModelState(
                status = DhirModelStatus.ERROR,
                expectedFilename = EXPECTED_FILENAME_LOWER,
                activeFileName = existingFile.name,
                activeFilePath = existingFile.absolutePath,
                fileSizeBytes = validation.fileSizeBytes,
                totalDeviceRamMb = totalMb,
                availableDeviceRamMb = availMb,
                nativeBackendReady = nativeReady,
                statusDetail = validation.errorReason ?: "Invalid GGUF file."
            )
        }

        val isLoaded = !loadedFilePath.isNullOrBlank() && loadedFilePath == existingFile.absolutePath
        val recommendedCtx = recommendContextSize(
            totalRamMb = totalMb,
            availableRamMb = availMb,
            modelSizeBytes = existingFile.length(),
            isLowRamDevice = lowMem
        )
        return DhirModelState(
            status = if (isLoaded) DhirModelStatus.LOADED else DhirModelStatus.READY_UNLOADED,
            expectedFilename = EXPECTED_FILENAME_LOWER,
            activeFileName = existingFile.name,
            activeFilePath = existingFile.absolutePath,
            fileSizeBytes = existingFile.length(),
            detectedArchitecture = arch.ifBlank { "dhir (GGUF v${validation.ggufVersion})" },
            effectiveContextSize = if (effectiveCtx > 0) effectiveCtx else recommendedCtx,
            totalDeviceRamMb = totalMb,
            availableDeviceRamMb = availMb,
            nativeBackendReady = nativeReady,
            statusDetail = if (isLoaded) {
                "Dhīr 2B loaded in local RAM (ctx=${if (effectiveCtx > 0) effectiveCtx else recommendedCtx}, ${availMb}MB free)."
            } else {
                val ramNote = when {
                    lowMem || (availMb in 1..900L) -> " Low free RAM (${availMb}MB free): using safe ${recommendedCtx} ctx."
                    totalMb in 1..3400L -> " Low-RAM device (${totalMb}MB): using ${recommendedCtx} ctx."
                    else -> ""
                }
                "GGUF verified (${formatMb(existingFile.length())}). Tap Load Model or send a prompt to start local inference.$ramNote"
            }
        )
    }

    fun updateRuntimeLoadedStatus(
        loadedPath: String?,
        isLoading: Boolean = false,
        effectiveCtx: Int = 0,
        arch: String = "",
        errorMessage: String? = null
    ) {
        if (errorMessage != null) {
            _state.value = inspectModelState().copy(
                status = DhirModelStatus.ERROR,
                statusDetail = errorMessage
            )
            return
        }
        if (isLoading) {
            _state.value = inspectModelState().copy(
                status = DhirModelStatus.LOADING,
                statusDetail = "Mapping dhir-2b-Q4_K_M.gguf into llama.cpp memory…"
            )
            return
        }
        _state.value = inspectModelState(loadedFilePath = loadedPath, effectiveCtx = effectiveCtx, arch = arch)
    }

    suspend fun importFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        val originalName = resolveDisplayName(uri) ?: EXPECTED_FILENAME_LOWER
        if (!originalName.endsWith(".gguf", ignoreCase = true)) {
            return@withContext Result.failure(
                IllegalArgumentException("Only .gguf model files are supported. Selected: $originalName")
            )
        }
        if (!originalName.contains("dhir", ignoreCase = true)) {
            return@withContext Result.failure(
                IllegalArgumentException("Please select the Dhīr 2B model file (dhir-2b-Q4_K_M.gguf).")
            )
        }

        val sanitizedFileName = originalName.substringAfterLast('/').substringAfterLast('\\')
        val partFile = File(modelsDir, "$sanitizedFileName.part")
        val targetFile = File(modelsDir, sanitizedFileName)

        _state.value = _state.value.copy(
            status = DhirModelStatus.IMPORTING,
            downloadProgressPercent = 0,
            statusDetail = "Importing $sanitizedFileName to app-private storage (original file unmodified)…"
        )

        try {
            val totalBytes = queryFileSize(uri)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copied += read
                        if (totalBytes > 0L) {
                            val pct = ((copied * 100L) / totalBytes).toInt().coerceIn(0, 99)
                            if (pct != _state.value.downloadProgressPercent) {
                                _state.value = _state.value.copy(
                                    downloadProgressPercent = pct,
                                    statusDetail = "Importing $sanitizedFileName ($pct%)…"
                                )
                            }
                        }
                    }
                    output.flush()
                }
            } ?: error("Unable to open selected file stream.")

            val check = validateGgufFile(partFile)
            if (!check.isValid) {
                partFile.delete()
                error(check.errorReason ?: "Selected file is not a valid GGUF binary.")
            }

            if (targetFile.exists()) targetFile.delete()
            check(partFile.renameTo(targetFile)) { "Failed to finalize model file in ${modelsDir.absolutePath}." }

            val updated = inspectModelState()
            _state.value = updated
            Result.success(targetFile)
        } catch (e: CancellationException) {
            partFile.delete()
            refreshState()
            throw e
        } catch (e: Exception) {
            partFile.delete()
            val msg = e.localizedMessage ?: "GGUF import failed."
            _state.value = inspectModelState().copy(status = DhirModelStatus.ERROR, statusDetail = msg)
            Result.failure(e)
        }
    }

    suspend fun downloadOfficialDhirModel(
        downloadUrl: String = OFFICIAL_HF_DOWNLOAD_URL
    ): Result<File> = withContext(Dispatchers.IO) {
        downloadCancelled = false
        val targetName = OFFICIAL_REPO_FILENAME
        val partFile = File(modelsDir, "$targetName.part")
        val targetFile = File(modelsDir, targetName)

        _state.value = _state.value.copy(
            status = DhirModelStatus.DOWNLOADING,
            downloadProgressPercent = 0,
            statusDetail = "Connecting to HuggingFace ($OFFICIAL_REPO_FILENAME)…"
        )

        try {
            val req = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "PB30JAI-FRIDAY-Android/1.0")
                .build()

            downloadClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Download failed (HTTP ${response.code}). You can also import dhir-2b-Q4_K_M.gguf manually.")
                }
                val body = response.body ?: error("Empty download response body.")
                val totalBytes = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (downloadCancelled) {
                                error("Download cancelled by operator.")
                            }
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0L) {
                                val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
                                if (pct != _state.value.downloadProgressPercent) {
                                    _state.value = _state.value.copy(
                                        downloadProgressPercent = pct,
                                        statusDetail = "Downloading $targetName: ${formatMb(downloaded)} / ${formatMb(totalBytes)} ($pct%)"
                                    )
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }

            val check = validateGgufFile(partFile)
            if (!check.isValid) {
                partFile.delete()
                error(check.errorReason ?: "Downloaded file failed GGUF header verification.")
            }

            if (targetFile.exists()) targetFile.delete()
            check(partFile.renameTo(targetFile)) { "Could not move verified model file into ${modelsDir.absolutePath}." }

            val updated = inspectModelState()
            _state.value = updated
            Result.success(targetFile)
        } catch (e: CancellationException) {
            partFile.delete()
            refreshState()
            throw e
        } catch (e: Exception) {
            partFile.delete()
            val msg = e.localizedMessage ?: "Model download failed."
            _state.value = inspectModelState().copy(status = DhirModelStatus.ERROR, statusDetail = msg)
            Result.failure(e)
        }
    }

    fun cancelDownload() {
        downloadCancelled = true
    }

    fun deleteInstalledModel(): Boolean {
        val deleted = modelsDir.listFiles()?.all { it.delete() } ?: true
        refreshState()
        return deleted
    }

    fun findInstalledDhirFile(): File? {
        val files = modelsDir.listFiles() ?: return null
        return files.firstOrNull { f ->
            f.isFile &&
                f.name.endsWith(".gguf", ignoreCase = true) &&
                !f.name.endsWith(".part", ignoreCase = true) &&
                f.name.contains("dhir", ignoreCase = true)
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun queryFileSize(uri: Uri): Long {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            }
        }.getOrDefault(-1L) ?: -1L
    }

    companion object {
        const val EXPECTED_FILENAME_LOWER = "dhir-2b-Q4_K_M.gguf"
        const val OFFICIAL_REPO_FILENAME = "Dhir-2B-Q4_K_M.gguf"
        const val OFFICIAL_HF_DOWNLOAD_URL =
            "https://huggingface.co/zenteiq-ai/Dhir-2B-GGUF/resolve/main/Dhir-2B-Q4_K_M.gguf?download=true"
        private const val GGUF_MAGIC_LE = 0x46554747 // 'G','G','U','F'
        private const val MIN_VALID_GGUF_BYTES = 1024L * 1024L // 1 MB header+payload sanity floor

        fun validateGgufFile(file: File): GgufValidationResult {
            if (!file.exists() || !file.isFile) {
                return GgufValidationResult(false, 0L, errorReason = "GGUF file does not exist.")
            }
            val size = file.length()
            if (size < MIN_VALID_GGUF_BYTES) {
                return GgufValidationResult(
                    false,
                    size,
                    errorReason = "File too small (${size} bytes) to be dhir-2b-Q4_K_M.gguf (~1.29 GB expected)."
                )
            }
            return runCatching {
                val header = ByteArray(8)
                FileInputStream(file).use { fis ->
                    val read = fis.read(header)
                    if (read < 8) {
                        return@runCatching GgufValidationResult(false, size, errorReason = "Truncated GGUF header.")
                    }
                }
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val magic = buf.int
                val version = buf.int
                if (magic != GGUF_MAGIC_LE) {
                    GgufValidationResult(false, size, errorReason = "Invalid GGUF magic header (expected GGUF).")
                } else if (version !in 1..5) {
                    GgufValidationResult(false, size, ggufVersion = version, errorReason = "Unsupported GGUF version: $version")
                } else {
                    GgufValidationResult(true, size, ggufVersion = version)
                }
            }.getOrElse { e ->
                GgufValidationResult(false, size, errorReason = "Could not inspect GGUF file: ${e.message}")
            }
        }

        fun recommendContextSize(
            totalRamMb: Long,
            requestedCtx: Int = 4096,
            availableRamMb: Long = -1L,
            modelSizeBytes: Long = 0L,
            isLowRamDevice: Boolean = false
        ): Int {
            val totalCap = when {
                totalRamMb <= 0L -> 2048
                totalRamMb < 3200L -> 1536
                totalRamMb < 5200L -> 2048
                else -> 4096
            }
            val modelMb = (modelSizeBytes / (1024L * 1024L)).coerceAtLeast(0L)
            val effectiveAvailMb = if (availableRamMb >= 0L) {
                val extraModelReserve = (modelMb - 1400L).coerceAtLeast(0L) / 2L
                (availableRamMb - extraModelReserve).coerceAtLeast(0L)
            } else {
                -1L
            }
            val availCap = when {
                isLowRamDevice && (effectiveAvailMb in 0L..750L) -> 512
                isLowRamDevice -> 1024
                effectiveAvailMb < 0L -> totalCap
                effectiveAvailMb < 750L -> 512
                effectiveAvailMb < 1150L -> 1024
                effectiveAvailMb < 1650L -> 1536
                effectiveAvailMb < 2400L -> 2048
                else -> 4096
            }
            val safeCap = minOf(totalCap, availCap)
            return requestedCtx.coerceAtMost(safeCap).coerceAtLeast(512)
        }

        fun checkLoadMemorySafety(context: Context, modelSizeBytes: Long): String? {
            val (_, availMb, lowMem) = readDeviceMemoryInfo(context)
            if (lowMem || (availMb in 1..449L)) {
                return "Available RAM is critically low (${availMb}MB free). Close background apps before loading dhir-2b-Q4_K_M.gguf (~${formatMb(modelSizeBytes)})."
            }
            return null
        }

        private data class DeviceMemorySnapshot(
            val totalMb: Long,
            val availMb: Long,
            val lowMemory: Boolean
        )

        private fun readDeviceMemoryInfo(context: Context): DeviceMemorySnapshot {
            return runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return DeviceMemorySnapshot(0L, 0L, false)
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                DeviceMemorySnapshot(
                    totalMb = info.totalMem / (1024L * 1024L),
                    availMb = info.availMem / (1024L * 1024L),
                    lowMemory = info.lowMemory || am.isLowRamDevice
                )
            }.getOrDefault(DeviceMemorySnapshot(0L, 0L, false))
        }

        private fun formatMb(bytes: Long): String {
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024.0) String.format("%.2f GB", mb / 1024.0)
            else String.format("%.0f MB", mb)
        }

        private val activeUnloadCallbacks = java.util.Collections.synchronizedSet(mutableSetOf<() -> Unit>())

        fun registerMemoryUnloadHandler(onTrimUnload: () -> Unit): () -> Unit {
            activeUnloadCallbacks.add(onTrimUnload)
            return { activeUnloadCallbacks.remove(onTrimUnload) }
        }

        @Suppress("DEPRECATION")
        fun onSystemTrimMemory(level: Int) {
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            ) {
                val handlers = synchronized(activeUnloadCallbacks) { activeUnloadCallbacks.toList() }
                handlers.forEach { runCatching { it.invoke() } }
            }
        }
    }
}
