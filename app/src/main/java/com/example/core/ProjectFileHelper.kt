package com.example.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.model.LoadedProjectFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater

/**
 * Zero-permission Storage Access Framework (SAF) file reader & writer for File Agent.
 * Supports text/code/config/logs, UTF-8/UTF-16 BOM encoding detection, chunked large-file reads,
 * and built-in PDF text extraction without external paid dependencies.
 */
object ProjectFileHelper {

    private const val MAX_TEXT_READ_BYTES = 1024 * 1024 // 1 MB text read cap
    private const val MAX_PDF_READ_BYTES = 8 * 1024 * 1024 // 8 MB PDF cap
    private const val MAX_PDF_STREAM_OUT_BYTES = 2 * 1024 * 1024 // Zip-bomb safety cap per stream
    const val DEFAULT_AI_CHUNK_CHARS = 12_000

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

                val isPdf = mimeType.equals("application/pdf", ignoreCase = true) ||
                    fileName.endsWith(".pdf", ignoreCase = true)
                val byteCap = if (isPdf) MAX_PDF_READ_BYTES else MAX_TEXT_READ_BYTES

                val rawBytes = resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var total = 0
                    while (total < byteCap + 1) {
                        val read = input.read(chunk)
                        if (read <= 0) break
                        val allowed = minOf(read, (byteCap + 1) - total)
                        buffer.write(chunk, 0, allowed)
                        total += allowed
                    }
                    buffer.toByteArray()
                } ?: return@withContext Result.failure(IllegalStateException("Unable to open selected file stream."))

                parseLoadedBytes(
                    uriString = uri.toString(),
                    fileName = fileName,
                    mimeType = mimeType,
                    reportedSize = reportedSize,
                    rawBytes = rawBytes
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    internal fun parseLoadedBytes(
        uriString: String,
        fileName: String,
        mimeType: String,
        reportedSize: Long,
        rawBytes: ByteArray
    ): Result<LoadedProjectFile> {
        val looksLikePdf = mimeType.equals("application/pdf", ignoreCase = true) ||
            fileName.endsWith(".pdf", ignoreCase = true) ||
            (rawBytes.size >= 5 && String(rawBytes, 0, 5, StandardCharsets.US_ASCII) == "%PDF-")

        if (looksLikePdf) {
            if (rawBytes.size > MAX_PDF_READ_BYTES) {
                return Result.failure(
                    IllegalArgumentException("PDF '$fileName' exceeds 8 MB safety limit (${rawBytes.size / (1024 * 1024)} MB). Split the PDF or select a smaller document.")
                )
            }
            val extracted = extractPdfText(rawBytes)
            val fullText = extracted.text
            if (fullText.isBlank()) {
                return Result.failure(
                    IllegalArgumentException(
                        "PDF '$fileName' (${extracted.pageCount} page(s)) contains no selectable text stream (likely scanned images/vector curves)."
                    )
                )
            }
            val chunkCount = maxOf(1, (fullText.length + DEFAULT_AI_CHUNK_CHARS - 1) / DEFAULT_AI_CHUNK_CHARS)
            val truncatedForAi = fullText.length > DEFAULT_AI_CHUNK_CHARS
            val warning = buildTruncationNotice(
                fileName = fileName,
                extractedChars = fullText.length,
                totalBytes = if (reportedSize >= 0) reportedSize else rawBytes.size.toLong(),
                bytesTruncated = false,
                chunkIndex = 0,
                chunkCount = chunkCount,
                extraNote = "PDF text extracted (${extracted.pageCount} page(s))."
            )
            return Result.success(
                LoadedProjectFile(
                    uriString = uriString,
                    fileName = fileName,
                    mimeType = if (mimeType == "text/plain") "application/pdf" else mimeType,
                    sizeBytes = if (reportedSize >= 0) reportedSize else rawBytes.size.toLong(),
                    content = fullText,
                    truncated = truncatedForAi,
                    encodingLabel = "PDF Text (${extracted.pageCount}p)",
                    totalCharsExtracted = fullText.length,
                    chunksCount = chunkCount,
                    currentChunkIndex = 0,
                    partialReadWarning = warning
                )
            )
        }

        val bytesTruncated = rawBytes.size > MAX_TEXT_READ_BYTES
        val usableBytes = if (bytesTruncated) rawBytes.copyOf(MAX_TEXT_READ_BYTES) else rawBytes

        val decoded = decodeTextSafely(usableBytes)
            ?: return Result.failure(
                IllegalArgumentException("'$fileName' appears to be a binary or unsupported file. File Agent inspects text, code, logs, config files, and selectable PDFs.")
            )

        val text = decoded.first
        val encodingLabel = decoded.second
        val chunkCount = maxOf(1, (text.length + DEFAULT_AI_CHUNK_CHARS - 1) / DEFAULT_AI_CHUNK_CHARS)
        val truncated = bytesTruncated || text.length > DEFAULT_AI_CHUNK_CHARS
        val totalSize = if (reportedSize >= 0) reportedSize else usableBytes.size.toLong()
        val warning = buildTruncationNotice(
            fileName = fileName,
            extractedChars = text.length,
            totalBytes = totalSize,
            bytesTruncated = bytesTruncated,
            chunkIndex = 0,
            chunkCount = chunkCount
        )

        return Result.success(
            LoadedProjectFile(
                uriString = uriString,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = totalSize,
                content = text,
                truncated = truncated,
                encodingLabel = encodingLabel,
                totalCharsExtracted = text.length,
                chunksCount = chunkCount,
                currentChunkIndex = 0,
                partialReadWarning = warning
            )
        )
    }

    fun getChunk(content: String, chunkIndex: Int, chunkChars: Int = DEFAULT_AI_CHUNK_CHARS): String {
        if (content.isEmpty()) return ""
        val totalChunks = maxOf(1, (content.length + chunkChars - 1) / chunkChars)
        val safeIdx = chunkIndex.coerceIn(0, totalChunks - 1)
        val start = safeIdx * chunkChars
        val end = minOf(content.length, start + chunkChars)
        return content.substring(start, end)
    }

    fun buildTruncationNotice(
        fileName: String,
        extractedChars: Int,
        totalBytes: Long,
        bytesTruncated: Boolean,
        chunkIndex: Int,
        chunkCount: Int,
        extraNote: String? = null
    ): String? {
        if (!bytesTruncated && chunkCount <= 1 && extraNote == null) return null
        return buildString {
            if (extraNote != null) append(extraNote).append(" ")
            if (bytesTruncated) {
                append("PARTIAL READ: '$fileName' (${totalBytes / 1024} KB) exceeded 1 MB cap; only first 1 MB loaded. ")
            }
            if (chunkCount > 1) {
                append("Chunk ${chunkIndex + 1}/$chunkCount (${minOf(DEFAULT_AI_CHUNK_CHARS, extractedChars)} of $extractedChars chars sent per pass).")
            }
        }.trim().ifBlank { null }
    }

    internal fun decodeTextSafely(bytes: ByteArray): Pair<String, String>? {
        if (bytes.isEmpty()) return "" to "UTF-8"

        // Check UTF-16 BOMs before binary NUL-byte check
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE) to "UTF-16BE"
            }
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE) to "UTF-16LE"
            }
        }
        val startOffset = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) 3 else 0

        val sampleLen = minOf(bytes.size - startOffset, 1024)
        var nulCount = 0
        var ctrlCount = 0
        for (i in startOffset until (startOffset + sampleLen)) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) nulCount++
            else if (b < 0x20 && b != '\n'.code && b != '\r'.code && b != '\t'.code) ctrlCount++
        }
        if (nulCount > 2 || (sampleLen > 32 && ctrlCount > sampleLen / 4)) {
            return null
        }

        val slice = if (startOffset == 0) bytes else bytes.copyOfRange(startOffset, bytes.size)
        val utf8Decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)

        return runCatching {
            val decoded = utf8Decoder.decode(java.nio.ByteBuffer.wrap(slice)).toString()
            decoded to if (startOffset == 3) "UTF-8 BOM" else "UTF-8"
        }.getOrElse {
            String(slice, Charset.forName("ISO-8859-1")) to "ISO-8859-1"
        }
    }

    internal data class PdfExtractionResult(
        val text: String,
        val pageCount: Int
    )

    internal fun extractPdfText(pdfBytes: ByteArray): PdfExtractionResult {
        val rawLatin = String(pdfBytes, StandardCharsets.ISO_8859_1)
        val pageMatches = Regex("""/Type\s*/Page\b(?!s)""").findAll(rawLatin).count()
        val pageCount = maxOf(pageMatches, 1)

        val outText = StringBuilder()
        var searchFrom = 0
        while (searchFrom < rawLatin.length) {
            val streamKw = rawLatin.indexOf("stream", searchFrom)
            if (streamKw < 0) break
            // Confirm header dictionary precedes stream cleanly and find newline right after "stream"
            val headerWindowStart = maxOf(0, streamKw - 400)
            val headerSnippet = rawLatin.substring(headerWindowStart, streamKw)
            val dataStart = when {
                rawLatin.startsWith("stream\r\n", streamKw) -> streamKw + 8
                rawLatin.startsWith("stream\n", streamKw) -> streamKw + 7
                rawLatin.startsWith("stream\r", streamKw) -> streamKw + 7
                else -> {
                    searchFrom = streamKw + 6
                    continue
                }
            }
            val endStream = rawLatin.indexOf("endstream", dataStart)
            if (endStream < 0) break

            val isFlate = headerSnippet.contains("/FlateDecode")
            val rawSlice = pdfBytes.copyOfRange(dataStart, endStream)
            val contentBytes = if (isFlate) inflateZlibSafely(rawSlice) else rawSlice
            if (contentBytes != null) {
                val contentStr = String(contentBytes, StandardCharsets.ISO_8859_1)
                if (contentStr.contains("BT") && (contentStr.contains("Tj") || contentStr.contains("TJ"))) {
                    val extracted = extractTextOperators(contentStr)
                    if (extracted.isNotBlank()) {
                        if (outText.isNotEmpty()) outText.append('\n')
                        outText.append(extracted)
                    }
                }
            }
            searchFrom = endStream + 9
        }

        // Handle PDFs that place readable BT..ET operators outside streams
        if (outText.isEmpty() && rawLatin.contains("BT") && rawLatin.contains("Tj")) {
            outText.append(extractTextOperators(rawLatin))
        }

        return PdfExtractionResult(
            text = outText.toString().replace(Regex("""[ \t]+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim(),
            pageCount = pageCount
        )
    }

    private fun inflateZlibSafely(compressed: ByteArray): ByteArray? {
        fun tryInflate(nowrap: Boolean): ByteArray? {
            val inflater = Inflater(nowrap)
            return try {
                inflater.setInput(compressed)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (!inflater.finished() && out.size() < MAX_PDF_STREAM_OUT_BYTES) {
                    val count = inflater.inflate(buf)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        out.write(buf, 0, count)
                    }
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            } finally {
                inflater.end()
            }
        }
        return tryInflate(false) ?: tryInflate(true)
    }

    private fun extractTextOperators(streamStr: String): String {
        val sb = StringBuilder()
        var idx = 0
        val len = streamStr.length
        while (idx < len) {
            val bt = streamStr.indexOf("BT", idx)
            if (bt < 0) break
            val et = streamStr.indexOf("ET", bt + 2)
            if (et < 0) break
            val block = streamStr.substring(bt + 2, et)
            var p = 0
            while (p < block.length) {
                when (block[p]) {
                    '(' -> {
                        val parsed = StringBuilder()
                        p++
                        var depth = 1
                        while (p < block.length && depth > 0) {
                            val ch = block[p]
                            if (ch == '\\' && p + 1 < block.length) {
                                val next = block[p + 1]
                                when (next) {
                                    'n' -> parsed.append('\n')
                                    'r' -> parsed.append('\r')
                                    't' -> parsed.append('\t')
                                    '(', ')', '\\' -> parsed.append(next)
                                    in '0'..'7' -> {
                                        var oct = ""
                                        var k = 0
                                        while (k < 3 && p + 1 + k < block.length && block[p + 1 + k] in '0'..'7') {
                                            oct += block[p + 1 + k]
                                            k++
                                        }
                                        parsed.append(oct.toInt(8).toChar())
                                        p += k - 1
                                    }
                                    else -> parsed.append(next)
                                }
                                p += 2
                            } else if (ch == '(') {
                                depth++
                                parsed.append(ch)
                                p++
                            } else if (ch == ')') {
                                depth--
                                if (depth > 0) parsed.append(ch)
                                p++
                            } else {
                                parsed.append(ch)
                                p++
                            }
                        }
                        if (parsed.isNotEmpty()) sb.append(parsed)
                    }
                    '<' -> {
                        if (p + 1 < block.length && block[p + 1] != '<') {
                            val close = block.indexOf('>', p + 1)
                            if (close > p + 1) {
                                val hex = block.substring(p + 1, close).replace(Regex("""\s+"""), "")
                                sb.append(decodePdfHexString(hex))
                                p = close + 1
                            } else {
                                p++
                            }
                        } else {
                            p++
                        }
                    }
                    'T' -> {
                        if (p + 1 < block.length && (block[p + 1] == 'd' || block[p + 1] == 'D' || block[p + 1] == '*')) {
                            if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                            p += 2
                        } else {
                            p++
                        }
                    }
                    else -> p++
                }
            }
            if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
            idx = et + 2
        }
        return sb.toString()
    }

    private fun decodePdfHexString(hex: String): String {
        if (hex.length < 2) return ""
        val padded = if (hex.length % 2 == 1) "${hex}0" else hex
        val bytes = ByteArray(padded.length / 2) { i ->
            padded.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
        }
        return if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        } else {
            String(bytes, StandardCharsets.ISO_8859_1).filter { it == '\n' || it == '\t' || it.code >= 0x20 }
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
