package com.example.data.repository

import android.util.Log
import com.example.data.model.WebCitation
import com.example.data.model.YouTubeVideoSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String
)

data class FetchedWebPage(
    val url: String,
    val title: String,
    val textContent: String
)

/**
 * Real HTTP web research, website extraction, CVE lookup, and YouTube search +
 * caption transcript retrieval. Never returns fabricated or mock results.
 */
class WebToolsRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    suspend fun searchWeb(query: String, maxResults: Int = 5): Result<List<WebSearchHit>> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Search query cannot be empty."))
            }

            val combined = mutableListOf<WebSearchHit>()
            var lastError: Throwable? = null

            // 1. NVD CVE 2.0 public REST API if query references a CVE ID
            Regex("""\bCVE-\d{4}-\d{4,7}\b""", RegexOption.IGNORE_CASE).find(cleanQuery)?.value?.uppercase()?.let { cveId ->
                runCatching { fetchNvdCve(cveId) }
                    .onSuccess { hit -> if (hit != null) combined += hit }
                    .onFailure { lastError = it }
            }

            // 2. DuckDuckGo HTML lightweight search endpoint
            runCatching { queryDuckDuckGoHtml(cleanQuery, maxResults) }
                .onSuccess { hits -> combined += hits }
                .onFailure {
                    Log.w(TAG, "DuckDuckGo search failed: ${it.message}")
                    lastError = it
                }

            // 3. Wikipedia MediaWiki Search API (reliable JSON fallback/enrichment)
            if (combined.size < 2) {
                runCatching { queryWikipedia(cleanQuery, 3) }
                    .onSuccess { hits -> combined += hits }
                    .onFailure {
                        Log.w(TAG, "Wikipedia search failed: ${it.message}")
                        if (lastError == null) lastError = it
                    }
            }

            val deduped = combined.distinctBy { it.url }.take(maxResults)
            if (deduped.isEmpty() && lastError != null) {
                Result.failure(IllegalStateException("Web search unavailable: ${lastError?.localizedMessage ?: "network error"}"))
            } else {
                Result.success(deduped)
            }
        }

    suspend fun fetchWebsiteContent(rawUrl: String): Result<FetchedWebPage> =
        withContext(Dispatchers.IO) {
            val validationError = validateSafePublicHttpUrl(rawUrl)
            if (validationError != null) {
                return@withContext Result.failure(IllegalArgumentException(validationError))
            }
            val parsed = rawUrl.trim().toHttpUrlOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid URL format."))

            runCatching {
                val request = Request.Builder()
                    .url(parsed)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code} ${response.message} while fetching ${parsed.host}")
                    }
                    val body = response.body?.string().orEmpty().take(MAX_DOWNLOAD_CHARS)
                    val title = extractHtmlTitle(body).ifBlank { parsed.host }
                    val cleanText = stripHtmlToReadableText(body).take(6000)
                    if (cleanText.isBlank()) {
                        error("No readable text content found at ${parsed.host}.")
                    }
                    FetchedWebPage(
                        url = response.request.url.toString(),
                        title = title,
                        textContent = cleanText
                    )
                }
            }
        }

    suspend fun searchYouTubeAndTranscripts(
        queryOrUrl: String,
        maxVideos: Int = 3
    ): Result<List<YouTubeVideoSummary>> = withContext(Dispatchers.IO) {
        val clean = queryOrUrl.trim()
        if (clean.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("YouTube query or URL is empty."))
        }

        val directVideoId = extractYouTubeVideoId(clean)
        if (directVideoId != null) {
            return@withContext runCatching {
                val watchInfo = fetchYouTubeWatchAndTranscript(directVideoId)
                listOf(watchInfo)
            }
        }

        runCatching {
            val encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8.name())
            val req = Request.Builder()
                .url("https://www.youtube.com/results?search_query=$encoded&hl=en")
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")
                .build()
            val html = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("YouTube search failed with HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            val parsedVideos = parseYouTubeSearchHtml(html).take(maxVideos)
            if (parsedVideos.isEmpty()) return@runCatching emptyList()

            // Fetch real caption/transcript for the top video if available
            val first = parsedVideos.first()
            val enrichedFirst = runCatching { fetchYouTubeWatchAndTranscript(first.videoId) }
                .getOrElse { first.copy(transcriptExcerpt = "Transcript unavailable: ${it.localizedMessage}", transcriptAvailable = false) }
            listOf(enrichedFirst) + parsedVideos.drop(1)
        }
    }

    private fun fetchYouTubeWatchAndTranscript(videoId: String): YouTubeVideoSummary {
        val url = "https://www.youtube.com/watch?v=$videoId"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")
            .build()
        val html = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} loading YouTube video $videoId")
            resp.body?.string().orEmpty()
        }

        val title = extractHtmlTitle(html)
            .removeSuffix(" - YouTube")
            .ifBlank { "YouTube Video ($videoId)" }

        val playerJsonStr = extractBalancedJsonObject(html, "ytInitialPlayerResponse = ")
        var channel = "YouTube"
        var captionBaseUrl: String? = null

        if (!playerJsonStr.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(playerJsonStr)
                channel = root.optJSONObject("videoDetails")?.optString("author").orEmpty().ifBlank { "YouTube" }
                val tracks = root.optJSONObject("captions")
                    ?.optJSONObject("playerCaptionsTracklistRenderer")
                    ?.optJSONArray("captionTracks")
                if (tracks != null && tracks.length() > 0) {
                    var chosen: JSONObject? = null
                    for (i in 0 until tracks.length()) {
                        val t = tracks.getJSONObject(i)
                        val lang = t.optString("languageCode")
                        if (lang.startsWith("en") || lang.startsWith("hi")) {
                            chosen = t
                            break
                        }
                    }
                    chosen = chosen ?: tracks.getJSONObject(0)
                    captionBaseUrl = chosen?.optString("baseUrl")?.takeIf { it.isNotBlank() }
                }
            }
        }

        val transcriptText = captionBaseUrl?.let { capUrl ->
            runCatching {
                val capReq = Request.Builder()
                    .url(capUrl)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .build()
                client.newCall(capReq).execute().use { resp ->
                    if (!resp.isSuccessful) ""
                    else parseYouTubeTimedTextXml(resp.body?.string().orEmpty())
                }
            }.getOrDefault("")
        }.orEmpty()

        return YouTubeVideoSummary(
            videoId = videoId,
            title = title,
            channel = channel,
            url = url,
            transcriptExcerpt = if (transcriptText.isNotBlank()) transcriptText.take(3500)
            else "Public captions/transcript unavailable for this video.",
            transcriptAvailable = transcriptText.isNotBlank()
        )
    }

    private fun fetchNvdCve(cveId: String): WebSearchHit? {
        val url = "https://services.nvd.nist.gov/rest/json/cves/2.0?cveId=$cveId"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "PB30JAI-FRIDAY-Android/1.0")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val root = JSONObject(resp.body?.string().orEmpty())
            val vulns = root.optJSONArray("vulnerabilities") ?: return@use null
            if (vulns.length() == 0) return@use null
            val cveObj = vulns.getJSONObject(0).optJSONObject("cve") ?: return@use null
            val descriptions = cveObj.optJSONArray("descriptions")
            var desc = ""
            if (descriptions != null) {
                for (i in 0 until descriptions.length()) {
                    val d = descriptions.getJSONObject(i)
                    if (d.optString("lang") == "en") {
                        desc = d.optString("value")
                        break
                    }
                }
            }
            val cvss = cveObj.optJSONObject("metrics")
                ?.optJSONArray("cvssMetricV31")
                ?.optJSONObject(0)
                ?.optJSONObject("cvssData")
                ?.optDouble("baseScore", -1.0)
                ?.takeIf { it > 0 }
            val snippet = buildString {
                if (cvss != null) append("CVSS v3.1: $cvss. ")
                append(desc.ifBlank { "Official NVD vulnerability entry." })
            }
            WebSearchHit(
                title = "NVD Official: $cveId",
                url = "https://nvd.nist.gov/vuln/detail/$cveId",
                snippet = snippet.take(450),
                source = "NVD API"
            )
        }
    }

    private fun queryDuckDuckGoHtml(query: String, limit: Int): List<WebSearchHit> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val req = Request.Builder()
            .url("https://html.duckduckgo.com/html/?q=$encoded")
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html")
            .build()
        val html = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("DuckDuckGo HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        return parseDuckDuckGoHtml(html).take(limit)
    }

    private fun queryWikipedia(query: String, limit: Int): List<WebSearchHit> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&utf8=1&srlimit=$limit"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "PB30JAI-FRIDAY-Android/1.0")
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Wikipedia HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        val results = JSONObject(body).optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
        return buildList {
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val title = item.optString("title").trim()
                val snippet = decodeHtmlEntities(item.optString("snippet").replace(Regex("<[^>]+>"), " ")).trim()
                if (title.isNotEmpty()) {
                    val slug = URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8.name())
                    add(
                        WebSearchHit(
                            title = "$title — Wikipedia",
                            url = "https://en.wikipedia.org/wiki/$slug",
                            snippet = snippet,
                            source = "Wikipedia"
                        )
                    )
                }
            }
        }
    }

    internal fun parseDuckDuckGoHtml(html: String): List<WebSearchHit> {
        val linkRegex = Regex(
            """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )
        val snippetRegex = Regex(
            """class="[^"]*result__snippet[^"]*"[^>]*>([\s\S]*?)</(?:a|div|span)>""",
            RegexOption.IGNORE_CASE
        )
        val snippets = snippetRegex.findAll(html).map {
            decodeHtmlEntities(it.groupValues[1].replace(Regex("<[^>]+>"), " "))
                .replace(Regex("\\s+"), " ").trim()
        }.toList()

        return linkRegex.findAll(html).mapIndexedNotNull { idx, match ->
            val rawHref = decodeHtmlEntities(match.groupValues[1])
            val resolvedUrl = resolveDuckDuckGoRedirect(rawHref)
            val title = decodeHtmlEntities(match.groupValues[2].replace(Regex("<[^>]+>"), " "))
                .replace(Regex("\\s+"), " ").trim()
            if (resolvedUrl.startsWith("http") && title.isNotEmpty()) {
                WebSearchHit(
                    title = title,
                    url = resolvedUrl,
                    snippet = snippets.getOrElse(idx) { "" },
                    source = "DuckDuckGo"
                )
            } else null
        }.toList()
    }

    internal fun parseYouTubeSearchHtml(html: String): List<YouTubeVideoSummary> {
        val jsonStr = extractBalancedJsonObject(html, "ytInitialData = ") ?: return emptyList()
        val found = mutableListOf<YouTubeVideoSummary>()
        runCatching {
            val root = JSONObject(jsonStr)
            collectYouTubeVideoRenderers(root, found, limit = 6)
        }
        return found.distinctBy { it.videoId }
    }

    private fun collectYouTubeVideoRenderers(
        node: Any?,
        out: MutableList<YouTubeVideoSummary>,
        limit: Int
    ) {
        if (out.size >= limit) return
        when (node) {
            is JSONObject -> {
                if (node.has("videoRenderer")) {
                    val vr = node.getJSONObject("videoRenderer")
                    val videoId = vr.optString("videoId").trim()
                    val title = extractYouTubeText(vr.optJSONObject("title"))
                    val channel = extractYouTubeText(vr.optJSONObject("ownerText"))
                        .ifBlank { extractYouTubeText(vr.optJSONObject("longBylineText")) }
                        .ifBlank { "YouTube" }
                    val duration = vr.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
                    if (videoId.isNotEmpty() && title.isNotEmpty()) {
                        out += YouTubeVideoSummary(
                            videoId = videoId,
                            title = title,
                            channel = channel,
                            url = "https://www.youtube.com/watch?v=$videoId",
                            durationLabel = duration
                        )
                    }
                }
                val keys = node.keys()
                while (keys.hasNext() && out.size < limit) {
                    collectYouTubeVideoRenderers(node.opt(keys.next()), out, limit)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (out.size >= limit) break
                    collectYouTubeVideoRenderers(node.opt(i), out, limit)
                }
            }
        }
    }

    private fun extractYouTubeText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = obj.optJSONArray("runs") ?: return ""
        return buildString {
            for (i in 0 until runs.length()) {
                append(runs.optJSONObject(i)?.optString("text").orEmpty())
            }
        }.trim()
    }

    internal fun parseYouTubeTimedTextXml(xml: String): String {
        val textTagRegex = Regex("""<text[^>]*>([\s\S]*?)</text>""", RegexOption.IGNORE_CASE)
        val lines = textTagRegex.findAll(xml).mapNotNull { match ->
            decodeHtmlEntities(match.groupValues[1].replace(Regex("<[^>]+>"), " "))
                .replace(Regex("\\s+"), " ")
                .trim()
                .takeIf { it.isNotEmpty() }
        }.toList()
        if (lines.isNotEmpty()) return lines.joinToString(" ")

        // VTT or s-tag fallback
        return decodeHtmlEntities(xml.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    internal fun validateSafePublicHttpUrl(rawUrl: String): String? {
        val parsed = rawUrl.trim().toHttpUrlOrNull()
            ?: return "Invalid URL. Must start with https:// or http://"
        if (parsed.scheme != "https" && parsed.scheme != "http") {
            return "Only HTTP/HTTPS URLs are permitted."
        }
        val host = parsed.host.lowercase()
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) {
            return "Local/internal hostnames are blocked from web fetch for SSRF protection."
        }
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
        if (addresses != null) {
            for (addr in addresses) {
                if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress || addr.isMulticastAddress) {
                    return "Private/loopback/metadata IP targets are blocked from web fetch (${addr.hostAddress})."
                }
            }
        }
        return null
    }

    internal fun extractYouTubeVideoId(text: String): String? {
        val watchMatch = Regex("""(?:youtube\.com/watch\?[^"\s]*v=|youtu\.be/|youtube\.com/shorts/)([A-Za-z0-9_-]{11})""")
            .find(text)
        return watchMatch?.groupValues?.getOrNull(1)
    }

    internal fun extractHtmlTitle(html: String): String {
        val m = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE).find(html)
        return decodeHtmlEntities(m?.groupValues?.getOrNull(1).orEmpty())
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    internal fun stripHtmlToReadableText(html: String): String {
        val withoutBlocks = html
            .replace(Regex("""<(script|style|noscript|svg|nav|footer)[^>]*>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<br\s*/?>|</p>|</div>|</li>|</h[1-6]>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), " ")
        return decodeHtmlEntities(withoutBlocks)
            .lines()
            .map { it.replace(Regex("[ \t]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun resolveDuckDuckGoRedirect(href: String): String {
        val normalized = when {
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "https://duckduckgo.com$href"
            else -> href
        }
        val uddgMatch = Regex("""[?&]uddg=([^&]+)""").find(normalized)?.groupValues?.getOrNull(1)
        return if (!uddgMatch.isNullOrBlank()) {
            runCatching { URLDecoder.decode(uddgMatch, StandardCharsets.UTF_8.name()) }.getOrDefault(normalized)
        } else {
            normalized
        }
    }

    private fun decodeHtmlEntities(raw: String): String =
        raw.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("""&#(\d+);""")) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: ""
            }

    private fun extractBalancedJsonObject(source: String, marker: String): String? {
        val startMarkerIdx = source.indexOf(marker)
        if (startMarkerIdx < 0) return null
        val braceStart = source.indexOf('{', startMarkerIdx + marker.length)
        if (braceStart < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in braceStart until source.length) {
            val c = source[i]
            if (inString) {
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return source.substring(braceStart, i + 1)
                        }
                    }
                }
            }
        }
        return null
    }

    fun hitsToCitations(hits: List<WebSearchHit>): List<WebCitation> =
        hits.map { WebCitation(title = it.title, url = it.url) }.distinctBy { it.url }

    private companion object {
        const val TAG = "WebToolsRepository"
        const val MAX_DOWNLOAD_CHARS = 512 * 1024
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
