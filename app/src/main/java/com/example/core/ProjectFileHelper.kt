package com.example.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.model.LoadedProjectFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Zero-permission Storage Access Framework (SAF) file reader & writer for File Agent.
 */
object ProjectFileHelper {

    private const val MAX_READ_BYTES = 256 * 1024 // 256 KB safe limit

    suspend fun readDocument(context: Context, uri: Uri): Result<LoadedProjectFile> =
        withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val mimeType = resolver.getType(uri) ?: "text/plain"
                var fileName = "document.txt"
                var reportedSize = -1L

                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) reportedSize = cursor.getLong(sizeIdx)
                    }
                }

                val bytes = resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    var total = 0
                    while (total < MAX_READ_BYTES + 1) {
                        val read = input.read(chunk)
                        if (read <= 0) break
                        val allowed = minOf(read, (MAX_READ_BYTES + 1) - total)
                        buffer.write(chunk, 0, allowed)
                        total += allowed
                    }
                    buffer.toByteArray()
                } ?: return@withContext Result.failure(IllegalStateException("Unable to open selected file stream."))

                val truncated = bytes.size > MAX_READ_BYTES
                val usableBytes = if (truncated) bytes.copyOf(MAX_READ_BYTES) else bytes

                // Basic NUL-byte check for binary formats
                val nullBytes = usableBytes.take(512).count { it == 0.toByte() }
                if (nullBytes > 4) {
                    return@withContext Result.failure(
                        IllegalArgumentException("'$fileName' appears to be a binary file. File Agent inspects text, code, logs, and config files.")
                    )
                }

                val text = String(usableBytes, StandardCharsets.UTF_8)
                Result.success(
                    LoadedProjectFile(
                        uriString = uri.toString(),
                        fileName = fileName,
                        mimeType = mimeType,
                        sizeBytes = if (reportedSize >= 0) reportedSize else usableBytes.size.toLong(),
                        content = text,
                        truncated = truncated
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun writeDocument(context: Context, uri: Uri, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.write(content.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                } ?: return@withContext Result.failure(IllegalStateException("Target file is read-only. Use 'Save As Copy' instead."))
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
