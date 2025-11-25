package com.example.videodownloader.ui

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videodownloader.model.VideoItem
import kotlinx.coroutines.Dispatchers
import com.example.videodownloader.ffmpeg.HlsTrackManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder
import android.util.Log
// import com.arthenica.ffmpegkit.FFmpegKit
// import com.arthenica.ffmpegkit.SessionState
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.OutputStream
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

data class VideoDownloadUiState(
    val urlText: String = "",
    val videos: List<VideoItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class VideoDownloadViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(VideoDownloadUiState())
    val uiState: StateFlow<VideoDownloadUiState> = _uiState

    private val appContext: Context = application.applicationContext
    private val defaultUserAgent = "Mozilla/5.0 (Android) VideoDownloader/1.0"
    @Volatile private var currentReferer: String? = null
    @Volatile private var currentCookies: String? = null

    fun setCurrentPageUrl(url: String) {
        currentReferer = url
    }

    private fun parseInitMap(baseUrl: String, media: String): String? {
        // Örnek: #EXT-X-MAP:URI="init.mp4"
        val line = media.lines().firstOrNull { it.startsWith("#EXT-X-MAP:") } ?: return null
        val uriRaw = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.getOrNull(1) ?: return null
        return resolveUrl(baseUrl, uriRaw)
    }

    fun setCurrentCookies(cookies: String?) {
        currentCookies = cookies
    }

    fun onUrlChange(newUrl: String) {
        _uiState.value = _uiState.value.copy(urlText = newUrl, errorMessage = null)
    }

    fun onFetchVideos() {
        val url = _uiState.value.urlText.trim()
        if (!isValidVideoUrl(url)) {
            Toast.makeText(appContext, "Geçersiz video linki", Toast.LENGTH_SHORT).show()
            _uiState.value = _uiState.value.copy(videos = emptyList(), errorMessage = "Invalid URL")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val result = withContext(Dispatchers.IO) {
                try {
                    val html = fetchHtml(url)
                    val mp4Urls = extractMp4Urls(html, url).distinct()
                    val subHtml = extractSubtitleUrls(html, url)
                    val subAssets = fetchAndScanAssets(html, url)
                    // Eğer doğrudan bir JS/JSON asset URL'i verildiyse onu da tarayalım
                    val directAssetSubs = try {
                        if (url.endsWith(".js", true) || url.endsWith(".json", true)) {
                            val body = fetchText(url)
                            val found = extractSubtitleUrls(body, url)
                            Log.i("SubScan", "Direct asset hit: ${'$'}url -> ${'$'}{found.size}")
                            found
                        } else emptyList()
                    } catch (_: Exception) { emptyList() }
                    val subUrls = (subHtml + subAssets + directAssetSubs).distinctBy { it.first }
                    // Subtitle'ları SubtitleItem listesine dönüştür
                    val subtitleItems = subUrls.map { (u, label) ->
                        val format = when {
                            u.endsWith(".vtt", true) -> "vtt"
                            u.endsWith(".srt", true) -> "srt"
                            u.endsWith(".ass", true) -> "ass"
                            u.endsWith(".ssa", true) -> "ssa"
                            else -> "subtitle"
                        }
                        com.example.videodownloader.model.SubtitleItem(
                            url = u,
                            label = label,
                            format = format
                        )
                    }
                    // MP4'leri SubtitleItem'larla birlikte oluştur
                    val mp4Items = mp4Urls.mapIndexed { index, mp4Url ->
                        val size = getContentLength(mp4Url)
                        val title = buildTitleFromUrl(mp4Url, size)
                        VideoItem(
                            id = (index + 1).toString(),
                            title = title,
                            thumbnailUrl = "",
                            downloadUrl = mp4Url,
                            sizeBytes = size,
                            subtitles = subtitleItems // Tüm subtitle'ları her videoya ekle
                        )
                    }
                    // Eğer video yoksa subtitle'ları ayrı item olarak göster
                    val subBase = mp4Items.size
                    val subItems = if (mp4Items.isEmpty()) {
                        subUrls.mapIndexed { sidx, pair ->
                            val (u, label) = pair
                            VideoItem(
                                id = (subBase + sidx + 1).toString(),
                                title = "SUB ${label}",
                                thumbnailUrl = "",
                                downloadUrl = u,
                                sizeBytes = null
                            )
                        }
                    } else emptyList()
                    mp4Items + subItems
                } catch (e: Exception) {
                    null
                }
            }

            if (result == null || result.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    videos = emptyList(),
                    errorMessage = "No MP4 found"
                )
                Toast.makeText(appContext, "Sayfada indirilebilir MP4 bulunamadı", Toast.LENGTH_SHORT).show()
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    videos = result,
                    errorMessage = null
                )
            }
        }
    }

    // İçeriğin gerçekten m3u8 olup olmadığını küçük bir GET ile kontrol et
    private fun looksLikeM3u8(url: String): Boolean {
        val text = fetchText(url)
        // M3U8 playlist'ler genellikle #EXTM3U ile başlar
        return text.trimStart().startsWith("#EXTM3U")
    }

    private fun isValidVideoUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val isHttp = url.startsWith("http://") || url.startsWith("https://")
        if (!isHttp) return false

        // Mevcut sürümde gerçek ekstraksiyon yok; bu nedenle geniş kabul yapalım.
        // En azından bazı bilinen kalıpları ve dizibox'ı kapsaın.
        val lowered = url.lowercase()
        val allowedHints = listOf(
            "youtube.com",
            "youtu.be",
            "vimeo.com",
            "dailymotion.com",
            "m3u8",
            "mp4",
            "video",
            "dizibox.live"
        )
        return allowedHints.any { lowered.contains(it) } || isHttp
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor("AppHTTP"))
            .build()
    }

    private fun loggingInterceptor(tag: String = "HttpLog"): Interceptor = Interceptor { chain ->
        val req = chain.request()
        val start = System.nanoTime()
        try {
            Log.d(tag, "--> ${'$'}{req.method} ${'$'}{req.url}")
            req.headers.names().forEach { name ->
                req.headers.values(name).forEach { value -> Log.v(tag, "${'$'}name: ${'$'}value") }
            }
        } catch (_: Throwable) {}
        val resp = chain.proceed(req)
        val tookMs = (System.nanoTime() - start) / 1_000_000
        try {
            val peek = kotlin.runCatching { resp.peekBody(64 * 1024).string() }.getOrNull()
            Log.d(tag, "<-- ${'$'}{resp.code} ${'$'}{resp.message} (${tookMs}ms) ${'$'}{req.url}")
            if (!peek.isNullOrEmpty()) Log.v(tag, "body: ${peek.take(2000)}")
        } catch (_: Throwable) {}
        resp
    }

    // Test amaçlı: güvensiz SSL istemcisi (self-signed/eksik CA). Prod'da KULLANMAYIN.
    private fun unsafeClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            })
            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocketFactory = sslContext.socketFactory
            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor(loggingInterceptor("AppHTTP(UNSAFE)"))
                .build()
        } catch (e: Exception) {
            httpClient
        }
    }

    private fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val u = URI(url)
            val scheme = u.scheme ?: return null
            val host = u.host ?: return null
            val port = u.port
            if (port != -1 && port != 80 && port != 443) "$scheme://$host:$port" else "$scheme://$host"
        } catch (e: Exception) { null }
    }

    private fun fetchHtml(url: String): String {
        // Jsoup uses its own HTTP client; set a UA to avoid blocks
        return Jsoup.connect(url)
            .userAgent(defaultUserAgent)
            .referrer(url)
            .ignoreContentType(true)
            .get()
            .outerHtml()
    }

    private fun extractMp4Urls(html: String, baseUrl: String): List<String> {
        val doc = Jsoup.parse(html, baseUrl)
        val anchors = doc.select("a[href$=.mp4], a[href*=.mp4]")
        val fromAnchors = anchors.mapNotNull { a -> a.attr("abs:href").takeIf { url -> url.contains(".mp4", ignoreCase = true) } }

        val scripts = doc.select("script")
        val regex = Regex("https?://[^'\"\\s>]+\\.mp4", RegexOption.IGNORE_CASE)
        val fromScripts = scripts.flatMap { script -> regex.findAll(script.data()).map { match -> match.value } }

        return (fromAnchors + fromScripts)
    }

    // HTML/JS içinden harici altyazı URL'lerini yakala (VTT veya altyazı m3u8)
    private fun extractSubtitleUrls(html: String, baseUrl: String): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        fun addPair(u: String, label: String?) {
            val abs = try { resolveUrl(baseUrl, u) } catch (_: Exception) { u }
            out += Pair(abs, label ?: "subtitle")
        }
        fun parseJsonArray(arr: String) {
            val urlRegex = Regex("\"(url|src|file|vtt|srt|subtitle|subtitleUrl|subtitle_url|captions|captionsUrl|textTrackUrl|webvtt|vttUrl)\"\\s*:\\s*\"([^\\\"]+)\"", RegexOption.IGNORE_CASE)
            val labelRegex = Regex("\"(label|lang|language|name)\"\\s*:\\s*\"([^\\\"]*)\"", RegexOption.IGNORE_CASE)
            val labelMatch = labelRegex.find(arr)?.groupValues?.getOrNull(2)?.ifBlank { null }
            urlRegex.findAll(arr).forEach { m ->
                val rel = m.groupValues.getOrNull(2) ?: return@forEach
                addPair(rel, labelMatch)
            }
        }
        fun parseText(text: String) {
            // VTT, SRT, ASS/SSA formatlarını yakala
            val vttRegex = Regex("https?://[^'\"\\s>]+\\.(vtt|srt|ass|ssa)", RegexOption.IGNORE_CASE)
            vttRegex.findAll(text).forEach { 
                val ext = it.value.substringAfterLast('.').lowercase()
                addPair(it.value, ext) 
            }
            val m3u8Regex = Regex("https?://[^'\"\\s>]+\\.m3u8[^'\"\\s>]*", RegexOption.IGNORE_CASE)
            m3u8Regex.findAll(text).forEach { m ->
                val u = m.value
                if (u.contains("sub", true) || u.contains("vtt", true) || u.contains("cc", true) || u.contains("caption", true)) addPair(u, "subs")
            }
            // Şemasız VTT/SRT/ASS/M3U8 tespit (ör: \/api\/subs\/.vtt, /subtitle/xyz.srt)
            val bareSubtitle = Regex("(^|[^A-Za-z0-9_])([/A-Za-z0-9._%+-]*\\.(vtt|srt|ass|ssa))([^A-Za-z0-9_]|$)", setOf(RegexOption.IGNORE_CASE))
            bareSubtitle.findAll(text).forEach { m ->
                val u = m.groupValues.getOrNull(2) ?: return@forEach
                val ext = u.substringAfterLast('.').lowercase()
                addPair(u, ext)
            }
            val bareM3u8 = Regex("(^|[^A-Za-z0-9_])([/A-Za-z0-9._%+-]*\\.m3u8[^'\"\\s)]*)([^A-Za-z0-9_]|$)", setOf(RegexOption.IGNORE_CASE))
            bareM3u8.findAll(text).forEach { m ->
                val u = m.groupValues.getOrNull(2) ?: return@forEach
                if (u.contains("sub", true) || u.contains("vtt", true) || u.contains("cc", true) || u.contains("caption", true)) addPair(u, "subs")
            }
            val jsonBlocks = listOf(
                Regex("\"(subtitles|tracks|textTracks|captions|caption)\"\\s*:\\s*\\[(.*?)\\]", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                Regex("window\\.[A-Za-z0-9_$.]+\\s*=\\s*\\{(.*?)\\}", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            )
            for (r in jsonBlocks) {
                r.findAll(text).forEach { b ->
                    val arr = b.groupValues.getOrNull(2) ?: b.groupValues.getOrNull(1) ?: return@forEach
                    parseJsonArray(arr)
                }
            }
            // Playerjs init blokları: new Playerjs({ ... subtitle: [...] ... })
            val playerjsInit = Regex("new\\s+Playerjs\\s*\\(\\s*\\{(.*?)\\}\\s*\\)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            playerjsInit.findAll(text).forEach { b ->
                val obj = b.groupValues.getOrNull(1) ?: return@forEach
                // Playerjs 'subtitle' alanı genellikle dizi; ayrıca 'file' anahtarları olabilir
                val subArray = Regex("\\bsubtitle\\s*:\\s*\\[(.*?)\\]", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(obj)?.groupValues?.getOrNull(1)
                if (subArray != null) parseJsonArray(subArray)
                // Bazı temalarda 'subtitles' veya 'tracks' kullanabilir
                val subsAlt = Regex("\\b(subtitles|tracks)\\s*:\\s*\\[(.*?)\\]", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(obj)?.groupValues?.getOrNull(2)
                if (subsAlt != null) parseJsonArray(subsAlt)
            }
            // Playerjs runtime değişkenleri: o.subs[], o.files_subtitle[]
            val subsArrayPattern = Regex("\\bo\\.subs\\s*=\\s*\\[(.*?)\\]", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            subsArrayPattern.findAll(text).forEach { m ->
                val arr = m.groupValues.getOrNull(1) ?: return@forEach
                // o.subs dizisi doğrudan URL'leri içerir: ["url1.vtt", "url2.vtt"]
                Regex("""['\"]([^'\"]+\.(vtt|srt|ass|ssa))['\"]|['\"]([^'\"]*(?:sub|caption)[^'\"]*)['\"]|['\"]([^'\"]+)['\"]""")
                    .findAll(arr).forEach { urlMatch ->
                        val url = urlMatch.groupValues.getOrNull(1) 
                            ?: urlMatch.groupValues.getOrNull(3) 
                            ?: urlMatch.groupValues.getOrNull(4) 
                            ?: return@forEach
                        if (url.isNotBlank()) addPair(url, "playerjs")
                    }
            }
            // Playerjs decode edilmiş subtitle'lar (runtime execution sonrası)
            // window.Playerjs pattern'i - çalışma zamanında oluşan objeler
            val windowPlayerjs = Regex("window\\.Playerjs", RegexOption.IGNORE_CASE)
            if (windowPlayerjs.containsMatchIn(text)) {
                // Console log pattern'leri - debug modunda subtitle URL'leri loglanabilir
                val consoleLogSub = Regex("console\\.log\\([^)]*(?:sub|subtitle|caption)[^)]*['\"]([^'\"]+\\.(vtt|srt|ass|ssa))['\"][^)]*\\)", RegexOption.IGNORE_CASE)
                consoleLogSub.findAll(text).forEach { m ->
                    val url = m.groupValues.getOrNull(1) ?: return@forEach
                    addPair(url, "console-log")
                }
            }
            // Chromecast subtitle tracks: chrome.cast.media.Track
            val castTrackPattern = Regex("t\\.trackContentId\\s*=\\s*['\"]([^'\"]+)['\"]|trackContentId\\s*:\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
            castTrackPattern.findAll(text).forEach { m ->
                val url = m.groupValues.getOrNull(1) ?: m.groupValues.getOrNull(2) ?: return@forEach
                if (url.endsWith(".vtt", true) || url.contains("sub", true)) addPair(url, "chromecast")
            }
            // ŞİFRELİ içerik marker'ları - # ile başlayan encode edilmiş veriler
            // Bu tip URL'ler client-side decode edilir, doğrudan kullanılamaz
            val encryptedPattern = Regex("['\"]#([0-9A-Za-z]{10,})['\"]")
            encryptedPattern.findAll(text).forEach { m ->
                // Şifreli veri tespit edildi ama decode edilemez
                // Bu bilgiyi loglayalım
                Log.d("SubtitleExtract", "Encrypted content detected (needs client-side decode): #{...}")
            }
            // hls.js event/config içinde geçen olası alanlar
            val hlsCfg = Regex("hls\\s*=\\s*new\\s+Hls\\s*\\((.*?)\\)\\s*;", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            hlsCfg.findAll(text).forEach { b -> parseJsonArray(b.groupValues.getOrNull(1) ?: return@forEach) }
            val fetchLike = Regex("fetch\\(\\s*['\"]([^'\"]+\\.(vtt|m3u8)[^'\"]*)['\"]", RegexOption.IGNORE_CASE)
            fetchLike.findAll(text).forEach { m -> addPair(m.groupValues[1], null) }
        }
        fun decodeCandidates(s: String): List<String> {
            val list = mutableListOf<String>()
            list += s
            try { list += String(android.util.Base64.decode(s, android.util.Base64.DEFAULT)) } catch (_: Throwable) {}
            try { list += URLDecoder.decode(s, "UTF-8") } catch (_: Throwable) {}
            return list.distinct()
        }
        decodeCandidates(html).forEach { candidate ->
            try {
                parseText(candidate)
            } catch (_: Throwable) {}
        }
        return out.toList()
    }

    // HTML içindeki bağlı script/json asset'leri indirip tarar
    private fun fetchAndScanAssets(html: String, baseUrl: String, maxAssets: Int = 120): List<Pair<String, String>> {
        val results = LinkedHashSet<Pair<String, String>>()
        return try {
            val doc = Jsoup.parse(html, baseUrl)
            val scriptSrcs = doc.select("script[src]").mapNotNull { it.attr("abs:src").takeIf { s -> s.isNotBlank() } }
            val linkJsons = doc.select("link[type=application/json][href], link[rel=preload][as=fetch][href], link[rel=preload][as=script][href], link[rel=preload][as=json][href]")
                .mapNotNull { it.attr("abs:href").takeIf { s -> s.isNotBlank() } }
            val inlineJsons = doc.select("script[type=application/json], script[type=application/ld+json]").map { it.data() }
            val candidates = (scriptSrcs + linkJsons).distinct().take(maxAssets)
            inlineJsons.forEach { body ->
                val found = extractSubtitleUrls(body, baseUrl)
                if (found.isNotEmpty()) Log.i("SubScan", "Inline JSON hit: ${found.size}")
                found.forEach { results += it }
            }
            for (u in candidates) {
                Log.d("SubScan", "Scan asset: $u")
                val body = try { fetchText(u) } catch (e: Exception) { Log.d("SubScan", "Asset fetch failed: $u -> ${'$'}{e.message}"); null } ?: continue
                val found = extractSubtitleUrls(body, u)
                if (found.isNotEmpty()) Log.i("SubScan", "Asset hit: $u -> ${found.size}")
                found.forEach { results += it }
            }
            results.toList()
        } catch (_: Exception) { emptyList() }
    }

    private fun getContentLength(url: String): Long? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", defaultUserAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "tr-TR,tr;q=0.8,en-US;q=0.6,en;q=0.4")
                .header("Upgrade-Insecure-Requests", "1")
            currentReferer?.let { builder.header("Referer", it) }
            currentCookies?.let { builder.header("Cookie", it) }
            val req = builder.build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val header = resp.header("Content-Length")
                    val len = header?.toLongOrNull()
                    if (len != null) return len
                }
            }
            // Fallback: Range GET 0-0 to parse Content-Range: bytes 0-0/TOTAL
            val gb = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-0")
                .header("User-Agent", defaultUserAgent)
                .header("Accept", "*/*")
                .header("Accept-Language", "tr-TR,tr;q=0.8,en-US;q=0.6,en;q=0.4")
            currentReferer?.let { gb.header("Referer", it) }
            currentCookies?.let { gb.header("Cookie", it) }
            val greq = gb.build()
            httpClient.newCall(greq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val cr = resp.header("Content-Range")
                // Content-Range: bytes 0-0/12345
                val total = cr?.substringAfter('/')?.toLongOrNull()
                total ?: resp.header("Content-Length")?.toLongOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildTitleFromUrl(url: String, size: Long?): String {
        return try {
            val path = URI(url).path ?: url
            val name = path.substringAfterLast('/').ifBlank { url }
            if (size != null) "$name (${formatSize(size)})" else name
        } catch (e: Exception) {
            if (size != null) "Video (${formatSize(size)})" else "Video"
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes.toDouble() / (1024 * 1024)
        return String.format("%.1f MB", mb)
    }

    // Basit HLS boyut tahmini: master playlist varsa en yüksek BANDWIDTH seç,
    // media playlist'te toplam süreyi (EXTINF) topla, boyut = (bandwidth bits/s * süre) / 8
    private fun estimateHlsSize(url: String): Long? {
        return try {
            val master = fetchText(url)
            val isMaster = master.lines().any { line -> line.trimStart().startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
            val chosen = if (isMaster) {
                val variants = parseMasterVariants(master, url)
                variants.maxByOrNull { variant -> variant.bandwidth }
            } else null
            val mediaUrl = chosen?.url ?: url

            val media = fetchText(mediaUrl)
            val segs = parseSegmentUrls(mediaUrl, media)
            if (segs.isEmpty()) return null
            // Başta birkaç segmentin ortalama boyutunu hesapla (HEAD)
            val sampleCount = kotlin.math.min(5, segs.size)
            var sum = 0L
            var n = 0
            for (i in 0 until sampleCount) {
                val cl = getContentLength(segs[i])
                if (cl != null && cl > 0) { sum += cl; n++ }
            }
            val avg = if (n > 0) sum / n else null
            val initMap = parseInitMap(mediaUrl, media)
            val initSize = initMap?.let { getContentLength(it) } ?: 0L
            if (avg != null) {
                initSize + avg * segs.size
            } else {
                // Geri dönüş: bandwidth * süre yöntemi
                val durationSec = parsePlaylistDuration(media)
                val bandwidth = chosen?.bandwidth
                if (bandwidth == null || durationSec <= 0.0) return null
                val bits = bandwidth.toDouble() * durationSec
                (bits / 8.0).toLong()
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class HlsVariant(val url: String, val bandwidth: Long)

    private fun parseMasterVariants(master: String, baseUrl: String): List<HlsVariant> {
        val result = mutableListOf<HlsVariant>()
        val lines = master.lines()
        var bw: Long? = null
        for (i in lines.indices) {
            val line = lines[i].trimStart()
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val bwMatch = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE).find(line)
                bw = bwMatch?.groupValues?.get(1)?.toLongOrNull()
                // sonraki satırlardan ilk # ile başlamayan gerçek URI'yı bul
                var j = i + 1
                while (j < lines.size) {
                    val uriLine = lines[j].trim()
                    if (uriLine.isNotEmpty() && !uriLine.startsWith("#")) {
                        val abs = resolveUrl(baseUrl, uriLine)
                        if (bw != null) result.add(HlsVariant(abs, bw!!))
                        break
                    }
                    j++
                }
            }
        }
        return result
    }

    private fun parseBandwidthFromMaster(master: String): Long? {
        val matches = Regex("#EXT-X-STREAM-INF[^\n]*BANDWIDTH=(\\d+)").findAll(master)
        return matches.mapNotNull { match -> match.groupValues.getOrNull(1)?.toLongOrNull() }.maxOrNull()
    }

    private fun parsePlaylistDuration(media: String): Double {
        val regex = Regex("#EXTINF:([0-9.]+)")
        return regex.findAll(media).mapNotNull { match -> match.groupValues.getOrNull(1)?.toDoubleOrNull() }.sum()
    }

    private fun fetchText(url: String): String {
        val builder = Request.Builder().url(url).get()
        builder.header("User-Agent", defaultUserAgent)
        // m3u8 için doğru Accept başlığı kullan
        if (url.lowercase().contains(".m3u8")) {
            builder.header("Accept", "application/x-mpegURL,application/vnd.apple.mpegurl,*/*")
        } else {
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        }
        builder.header("Accept-Language", "tr-TR,tr;q=0.8,en-US;q=0.6,en;q=0.4")
        builder.header("Upgrade-Insecure-Requests", "1")
        currentReferer?.let { builder.header("Referer", it) }
        currentCookies?.let { builder.header("Cookie", it) }
        originOf(currentReferer)?.let { builder.header("Origin", it) }
        val req = builder.build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                resp.body?.string() ?: ""
            }
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            Log.w("HLS", "SSLHandshakeException on $url, retrying UNSAFE (test only)")
            val u = unsafeClient()
            u.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} (unsafe)")
                resp.body?.string() ?: ""
            }
        }
    }

    private fun resolveUrl(base: String, relative: String): String {
        return try { URL(URL(base), relative).toString() } catch (e: Exception) { relative }
    }

    fun downloadVideo(videoItem: VideoItem) {
        val url = videoItem.downloadUrl
        viewModelScope.launch {
            // Non-downloadable embedded captions
            if (url.startsWith("cc://") || videoItem.title.startsWith("CC ")) {
                Toast.makeText(appContext, "Gömülü (CLOSED-CAPTIONS) altyazı indirilemez", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // İçerik türüne göre karar ver (HEAD/GET ile Content-Type probe eden detectUrlKind)
            val kind = withContext(Dispatchers.IO) { try { detectUrlKind(url) } catch (_: Exception) { UrlKind.UNKNOWN } }
            // Bazı sunucular HEAD/Content-Type probunu engeller; uzantı ve içerik peek ile HLS olasılığını genişlet
            val hlsLikely = withContext(Dispatchers.IO) {
                try {
                    kind == UrlKind.M3U8 || url.lowercase().contains(".m3u8") || looksLikeM3u8(url)
                } catch (_: Exception) { kind == UrlKind.M3U8 || url.lowercase().contains(".m3u8") }
            }

            // Subtitle formatları (VTT, SRT, ASS, SSA)
            if (kind == UrlKind.VTT || kind == UrlKind.SRT || kind == UrlKind.ASS || kind == UrlKind.SSA) {
                val format = when (kind) {
                    UrlKind.VTT -> "vtt"
                    UrlKind.SRT -> "srt"
                    UrlKind.ASS -> "ass"
                    UrlKind.SSA -> "ssa"
                    else -> "vtt"
                }
                Log.i("Subtitle", "Subtitle clicked ($format): url=$url")
                
                // Subtitle'ları downloadSubtitle fonksiyonu ile indir
                if (videoItem.subtitles.isNotEmpty()) {
                    downloadSubtitle(videoItem.subtitles.first(), videoItem.title.removeSuffix(".${format}"))
                } else {
                    // Fallback: DownloadManager ile indir
                    val uri = try { Uri.parse(url) } catch (e: Exception) {
                        Toast.makeText(appContext, "İndirme linki geçersiz", Toast.LENGTH_SHORT).show(); return@launch
                    }
                    val request = DownloadManager.Request(uri).apply {
                        setTitle(videoItem.title)
                        setDescription("Altyazı indiriliyor...")
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${sanitizeFileName(videoItem.title)}.$format")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        addRequestHeader("User-Agent", defaultUserAgent)
                        currentReferer?.let { addRequestHeader("Referer", it) }
                        currentCookies?.let { addRequestHeader("Cookie", it) }
                    }
                    val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(appContext, "Altyazı indiriliyor: ${videoItem.title}", Toast.LENGTH_SHORT).show()
                    Log.i("Subtitle", "Subtitle DownloadManager enqueued: ${videoItem.title}")
                }
                return@launch
            }

            if (hlsLikely) {
                // HLS için özel indirme hattı
                Toast.makeText(appContext, "HLS indiriliyor...", Toast.LENGTH_SHORT).show()
                val ok = withContext(Dispatchers.IO) {
                    try {
                        if (videoItem.title.startsWith("SUB") || videoItem.title.startsWith("AUDIO SUB")) {
                            Log.i("HLS", "Subtitle clicked (HLS): url=$url")
                            startSubtitleDownload(url, sanitizeFileName(videoItem.title))
                        } else {
                            startHlsDownload(url, sanitizeFileName(videoItem.title))
                        }
                    } catch (e: Exception) { Log.w("HLS", "HLS download failed", e); false }
                }
                if (ok) {
                    Toast.makeText(appContext, "HLS indirme tamamlandı", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(appContext, "HLS indirme başarısız", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            // Normal dosya indirme (MP4 vb.) DownloadManager ile
            val uri = try { Uri.parse(url) } catch (e: Exception) {
                Toast.makeText(appContext, "İndirme linki geçersiz", Toast.LENGTH_SHORT).show(); return@launch
            }

            val request = DownloadManager.Request(uri).apply {
                setTitle(videoItem.title)
                setDescription("Video indiriliyor...")
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${sanitizeFileName(videoItem.title)}.mp4")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                addRequestHeader("User-Agent", defaultUserAgent)
                addRequestHeader("Accept", "*/*")
                addRequestHeader("Accept-Language", "tr-TR,tr;q=0.8,en-US;q=0.6,en;q=0.4")
                currentReferer?.let { addRequestHeader("Referer", it) }
                currentCookies?.let { addRequestHeader("Cookie", it) }
            }

            val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(appContext, "İndirme başlatıldı: ${videoItem.title}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sanitizeFileName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    /**
     * Subtitle dosyasını indir (VTT, SRT, ASS, SSA)
     * @param subtitleItem İndirilecek subtitle bilgisi
     * @param videoTitle İlişkili video başlığı (dosya adı için)
     */
    fun downloadSubtitle(subtitleItem: com.example.videodownloader.model.SubtitleItem, videoTitle: String = "") {
        viewModelScope.launch {
            val url = subtitleItem.url
            val format = subtitleItem.format
            
            // Dosya adını oluştur
            val baseName = if (videoTitle.isNotBlank()) {
                sanitizeFileName(videoTitle)
            } else {
                sanitizeFileName(subtitleItem.label)
            }
            val fileName = "$baseName.${format}"
            
            Log.i("SubtitleDownload", "Downloading subtitle: $url -> $fileName")
            
            try {
                // Subtitle içeriğini indir
                val content = withContext(Dispatchers.IO) {
                    val builder = Request.Builder()
                        .url(url)
                        .get()
                        .header("User-Agent", defaultUserAgent)
                        .header("Accept", "text/vtt,text/plain,*/*")
                    currentReferer?.let { builder.header("Referer", it) }
                    currentCookies?.let { builder.header("Cookie", it) }
                    
                    val req = builder.build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw Exception("HTTP ${resp.code}")
                        }
                        resp.body?.string() ?: throw Exception("Empty response")
                    }
                }
                
                // Dosyayı kaydet
                withContext(Dispatchers.IO) {
                    saveSubtitleFile(content, fileName, format)
                }
                
                Toast.makeText(appContext, "Altyazı indirildi: $fileName", Toast.LENGTH_LONG).show()
                Log.i("SubtitleDownload", "✅ Subtitle saved: $fileName")
                
            } catch (e: Exception) {
                Log.e("SubtitleDownload", "Failed to download subtitle: ${e.message}", e)
                Toast.makeText(appContext, "Altyazı indirilemedi: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * Subtitle dosyasını Downloads klasörüne kaydet
     */
    private fun saveSubtitleFile(content: String, fileName: String, format: String) {
        val mimeType = when (format.lowercase()) {
            "vtt" -> "text/vtt"
            "srt" -> "text/srt"
            "ass", "ssa" -> "text/x-ssa"
            else -> "text/plain"
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ MediaStore kullan
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Failed to create MediaStore entry")
            
            appContext.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw Exception("Failed to open output stream")
            
            Log.i("SubtitleDownload", "Saved via MediaStore: $uri")
        } else {
            // Android 9 ve altı için eski yöntem
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            file.writeText(content, Charsets.UTF_8)
            
            // MediaScanner'a bildir
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(file.absolutePath),
                arrayOf(mimeType)
            ) { _, _ -> }
            
            Log.i("SubtitleDownload", "Saved via File: ${file.absolutePath}")
        }
    }

    // HLS (.m3u8) indirme hattı: segmentleri indir ve TS olarak birleştir
    private fun startHlsDownload(hlsUrl: String, baseName: String): Boolean {
        Log.i("HLS", "Start download: $hlsUrl")
        // Altyazı otomatik tespit/çıkarma akışını paralel başlat (mevcut video/ses hattını bozmaz)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val headers = HlsTrackManager.Headers(
                    userAgent = defaultUserAgent,
                    referer = currentReferer,
                    cookie = currentCookies
                )
                val path = HlsTrackManager.findAndFetchSubtitle(appContext, hlsUrl, headers)
                if (path != null) {
                    Log.i("HlsSubtitle", "🎉 Altyazı bulundu ve kaydedildi: $path")
                } else {
                    Log.w("HlsSubtitle", "❌ Altyazı tespit edilemedi")
                }
            } catch (e: Exception) {
                Log.w("HlsSubtitle", "Subtitle auto flow failed: ${e.message}", e)
            }
        }
        // 1) Master/Media playlist çöz
        val master = fetchText(hlsUrl)
        val isMaster = master.lines().any { line -> line.trimStart().startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
        val chosen = if (isMaster) parseMasterVariants(master, hlsUrl).maxByOrNull { variant -> variant.bandwidth } else null
        val mediaUrl = chosen?.url ?: hlsUrl

        val media = fetchText(mediaUrl)
        val segmentUris = parseSegmentUrls(mediaUrl, media)
        if (segmentUris.isEmpty()) throw IllegalStateException("HLS segment bulunamadı")
        Log.i("HLS", "Media playlist: ${segmentUris.size} segments")

        // 1.b) Şifreleme bilgisi (AES-128) var mı?
        val keyInfo = parseKeyInfo(mediaUrl, media)
        // 1.c) fMP4 init segment (EXT-X-MAP) var mı?
        val initMap = parseInitMap(mediaUrl, media)

        // 2) Geçici klasör
        val tempDir = File(appContext.cacheDir, "hls_${System.currentTimeMillis()}")
        if (!tempDir.mkdirs()) { /* ignore */ }

        // 3) Segmentleri sırayla indir
        val segmentFiles = mutableListOf<File>()
        // Önce init segment
        if (initMap != null) {
            val initFile = File(tempDir, "part_init.mp4")
            Log.d("HLS", "Init segment indiriliyor")
            downloadBinary(initMap, initFile)
            segmentFiles.add(initFile)
        }
        val prevRef = currentReferer
        try {
            // Segment isteklerinde referer olarak media playlist URL'sini kullan
            currentReferer = mediaUrl
            segmentUris.forEachIndexed { idx, segUrl ->
                val part = File(tempDir, String.format("part_%05d.ts", idx))
                Log.d("HLS", "Segment indiriliyor: ${idx + 1}/${segmentUris.size}")
                if (keyInfo != null && keyInfo.method == "AES-128") {
                    downloadAndMaybeDecrypt(segUrl, part, keyInfo, idx)
                } else {
                    downloadBinary(segUrl, part)
                }
                segmentFiles.add(part)
            }
        } finally {
            currentReferer = prevRef
        }

        // 4) Birleştir
        val outDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir
        val outTs = File(outDir, "$baseName.ts")
        mergeSegments(segmentFiles, outTs)
        Log.i("HLS", "Birleştirme tamam: ${outTs.absolutePath}")
        try {
            val exported = exportToPublicDownloads(outTs, "$baseName.ts")
            if (exported != null) Log.i("HLS", "Genel Downloads'a kopyalandı: $exported")
        } catch (_: Exception) { }

        // 4.b) TS -> MP4 remux (temporarily disabled - FFmpegKit not available)
        // val outMp4 = File(outDir, "$baseName.mp4")
        // val cmd = "-hide_banner -y -i \"${outTs.absolutePath}\" -c copy -movflags +faststart \"${outMp4.absolutePath}\""
        // val session = FFmpegKit.execute(cmd)
        // if (session.state == SessionState.COMPLETED && session.returnCode.isValueSuccess) {
        //     Log.i("HLS", "MP4 oluşturuldu: ${outMp4.absolutePath}")
        //     outTs.delete()
        // } else {
        //     Log.w("HLS", "MP4 remux başarısız, TS tutuldu: code=${session.returnCode}")
        // }

        // 5) Temizle (isteğe bağlı)
        segmentFiles.forEach { file -> file.delete() }
        tempDir.delete()

        return true
    }

    private fun exportToPublicDownloads(src: File, displayName: String, mime: String? = null): Uri? {
        val resolver = appContext.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                if (mime != null) put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out -> FileInputStream(src).use { input -> input.copyTo(out) } }
            }
            uri
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val dst = File(dir, displayName)
            FileInputStream(src).use { input -> FileOutputStream(dst).use { out -> input.copyTo(out) } }
            MediaScannerConnection.scanFile(appContext, arrayOf(dst.absolutePath), arrayOf(mime ?: "application/octet-stream"), null)
            Uri.fromFile(dst)
        }
    }

    private fun parseSegmentUrls(baseUrl: String, media: String): List<String> {
        val list = mutableListOf<String>()
        val lines = media.lines()
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            // Her yorum olmayan satırı segment URI olarak kabul et (ts/m4s/mp4 veya uzantısız olabilir)
            list.add(resolveUrl(baseUrl, t))
        }
        return list
    }

    private data class KeyInfo(val method: String, val uri: String, val ivHex: String?)

    private fun parseKeyInfo(baseUrl: String, media: String): KeyInfo? {
        // Örnek: #EXT-X-KEY:METHOD=AES-128,URI="key.key",IV=0x012345...
        val line = media.lines().firstOrNull { it.startsWith("#EXT-X-KEY:") } ?: return null
        val method = Regex("METHOD=([^,]+)").find(line)?.groupValues?.getOrNull(1)
        val uriRaw = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.getOrNull(1)
        val iv = Regex("IV=0x([0-9A-Fa-f]+)").find(line)?.groupValues?.getOrNull(1)
        if (method == null || uriRaw == null) return null
        val keyUri = resolveUrl(baseUrl, uriRaw)
        return KeyInfo(method, keyUri, iv)
    }

    private fun downloadAndMaybeDecrypt(segUrl: String, dest: File, keyInfo: KeyInfo, index: Int) {
        // Anahtarı al
        val keyBytes = run {
            val b = Request.Builder().url(keyInfo.uri).get()
                .header("User-Agent", defaultUserAgent)
            currentReferer?.let { b.header("Referer", it) }
            currentCookies?.let { b.header("Cookie", it) }
            val req = b.build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Key HTTP ${'$'}{resp.code}")
                resp.body?.bytes() ?: throw IllegalStateException("Boş key")
            }
        }

        val ivBytes = when {
            keyInfo.ivHex != null -> hexToBytes(keyInfo.ivHex)
            else -> sequenceIv(index)
        }

        // Segmenti indir
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt < 3) {
            try {
                val builder = Request.Builder().url(segUrl).get()
                builder.header("User-Agent", defaultUserAgent)
                currentReferer?.let { builder.header("Referer", it) }
                currentCookies?.let { builder.header("Cookie", it) }
                originOf(currentReferer)?.let { builder.header("Origin", it) }
                val req = builder.build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${'$'}{resp.code}")
                    val ct = resp.header("Content-Type")?.lowercase()
                    if (ct != null && (ct.contains("text/html") || ct.contains("application/json"))) {
                        throw IllegalStateException("Yanıt türü desteklenmiyor: ${'$'}ct")
                    }
                    val body = resp.body ?: throw IllegalStateException("Boş içerik")
                    val plainBytes = if (keyInfo.method == "AES-128") {
                        decryptAes128(body.bytes(), keyBytes, ivBytes)
                    } else {
                        body.bytes()
                    }
                    FileOutputStream(dest).use { fos -> fos.write(plainBytes) }
                    return
                }
            } catch (e: Exception) {
                lastErr = e
                attempt++
                Thread.sleep(300L * attempt)
            }
        }
        throw lastErr ?: IllegalStateException("Segment indirilemedi")
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i / 2] = ((clean.substring(i, i + 2)).toInt(16)).toByte()
            i += 2
        }
        return out
    }

    private fun sequenceIv(index: Int): ByteArray {
        // 16 byte big-endian IV; basitçe index'i sona koy
        val iv = ByteArray(16)
        var v = index
        var p = 15
        while (v > 0 && p >= 0) {
            iv[p] = (v and 0xFF).toByte()
            v = v ushr 8
            p--
        }
        return iv
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        val skey = javax.crypto.spec.SecretKeySpec(key, "AES")
        val ivspec = javax.crypto.spec.IvParameterSpec(iv)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, skey, ivspec)
        return cipher.doFinal(data)
    }

    private fun downloadBinary(url: String, dest: File) {
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt < 3) {
            try {
                val builder = Request.Builder().url(url).get()
                builder.header("User-Agent", defaultUserAgent)
                currentReferer?.let { builder.header("Referer", it) }
                currentCookies?.let { builder.header("Cookie", it) }
                originOf(currentReferer)?.let { builder.header("Origin", it) }
                val req = builder.build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${'$'}{resp.code}")
                    val ct = resp.header("Content-Type")?.lowercase()
                    if (ct != null && (ct.contains("text/html") || ct.contains("application/json"))) {
                        throw IllegalStateException("Yanıt türü desteklenmiyor: ${'$'}ct")
                    }
                    val body = resp.body ?: throw IllegalStateException("Boş içerik")
                    FileOutputStream(dest).use { fos ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(8 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                fos.write(buf, 0, read)
                            }
                        }
                    }
                    return
                }
            } catch (e: Exception) {
                lastErr = e
                attempt++
                Thread.sleep(300L * attempt)
            }
        }
        throw lastErr ?: IllegalStateException("İndirme başarısız")
    }

    private fun mergeSegments(parts: List<File>, outFile: File) {
        FileOutputStream(outFile, false).use { fos ->
            val buf = ByteArray(64 * 1024)
            parts.forEach { part ->
                part.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                    }
                }
            }
        }
    }

    private enum class UrlKind { MP4, M3U8, VTT, SRT, ASS, SSA, UNKNOWN }

    private fun detectUrlKind(url: String): UrlKind {
        val l = url.lowercase()
        if (l.contains(".m3u8")) return UrlKind.M3U8
        if (l.contains(".vtt")) return UrlKind.VTT
        if (l.contains(".srt")) return UrlKind.SRT
        if (l.contains(".ass")) return UrlKind.ASS
        if (l.contains(".ssa")) return UrlKind.SSA
        if (l.contains(".mp4")) return UrlKind.MP4
        return try {
            val b = Request.Builder().url(url).head()
                .header("User-Agent", defaultUserAgent)
                .header("Accept", "*/*")
            currentReferer?.let { b.header("Referer", it) }
            currentCookies?.let { b.header("Cookie", it) }
            originOf(currentReferer)?.let { b.header("Origin", it) }
            httpClient.newCall(b.build()).execute().use { r ->
                val ct = r.header("Content-Type")?.lowercase()
                when {
                    ct == null -> UrlKind.UNKNOWN
                    ct.contains("mpegurl") || ct.contains("application/x-mpegurl") -> UrlKind.M3U8
                    ct.contains("text/vtt") || ct.contains("application/vtt") -> UrlKind.VTT
                    ct.contains("text/srt") || ct.contains("application/x-subrip") -> UrlKind.SRT
                    ct.contains("/x-ssa") || ct.contains("text/ssa") -> UrlKind.SSA
                    ct.contains("/x-ass") || ct.contains("text/ass") -> UrlKind.ASS
                    ct.startsWith("video/") || ct.contains("mp4") -> UrlKind.MP4
                    else -> UrlKind.UNKNOWN
                }
            }
        } catch (_: Exception) { UrlKind.UNKNOWN }
    }

    // Tarayıcı yakalayıcısından gelen medya URL'lerini işler
    fun onMediaFound(url: String) {
        val lowered = url.lowercase()
        if (isLikelyAdUrl(lowered)) return

        // Aynı URL listede varsa ekleme
        val exists = _uiState.value.videos.any { video -> video.downloadUrl == url }
        if (exists) return

        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                when (detectUrlKind(url)) {
                    UrlKind.MP4 -> {
                        val size = getContentLength(url)
                        val title = buildTitleFromUrl(url, size)
                        listOf(VideoItem(
                            id = (_uiState.value.videos.size + 1).toString(),
                            title = title,
                            thumbnailUrl = "",
                            downloadUrl = url,
                            sizeBytes = size
                        ))
                    }
                    UrlKind.VTT, UrlKind.SRT, UrlKind.ASS, UrlKind.SSA -> {
                        // Subtitle dosyası
                        val format = when (detectUrlKind(url)) {
                            UrlKind.VTT -> "vtt"
                            UrlKind.SRT -> "srt"
                            UrlKind.ASS -> "ass"
                            UrlKind.SSA -> "ssa"
                            else -> "subtitle"
                        }
                        val title = "SUB $format ${url.substringAfterLast('/').take(30)}"
                        listOf(VideoItem(
                            id = (_uiState.value.videos.size + 1).toString(),
                            title = title,
                            thumbnailUrl = "",
                            downloadUrl = url,
                            sizeBytes = null,
                            subtitles = listOf(
                                com.example.videodownloader.model.SubtitleItem(
                                    url = url,
                                    label = format,
                                    format = format
                                )
                            )
                        ))
                    }
                    UrlKind.M3U8 -> {
                        try {
                            Log.d("HLS", "Processing m3u8: $url")
                            val master = fetchText(url)
                            Log.d("HLS", "Master content length: ${master.length}")
                            val isMaster = master.lines().any { line -> line.trimStart().startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
                            Log.d("HLS", "Is master playlist: $isMaster")
                            
                            if (isMaster) {
                                // Master playlist: tüm kaliteleri listele
                                val variants = parseMasterVariants(master, url)
                                Log.d("HLS", "Found ${variants.size} variants")
                                variants.forEach { v -> Log.d("HLS", "Variant: ${v.bandwidth}bps -> ${v.url}") }
                                
                                val videoItems = if (variants.isNotEmpty()) {
                                    variants.mapIndexed { idx, variant ->
                                        val size = estimateHlsSize(variant.url)
                                        VideoItem(
                                            id = (_uiState.value.videos.size + idx + 1).toString(),
                                            title = "HLS ${formatBandwidth(variant.bandwidth)}",
                                            thumbnailUrl = "",
                                            downloadUrl = variant.url,
                                            sizeBytes = size
                                        )
                                    }
                                } else emptyList()

                                // Audio renditions: EXT-X-MEDIA TYPE=AUDIO
                                val audios: List<AudioRendition> = parseAudioRenditions(master, url)
                                Log.d("HLS", "Found ${audios.size} audio renditions")
                                val audioItems: List<VideoItem> = audios.mapIndexed { aidx: Int, a: AudioRendition ->
                                    val asize = estimateHlsSize(a.url)
                                    VideoItem(
                                        id = (_uiState.value.videos.size + videoItems.size + aidx + 1).toString(),
                                        title = "AUDIO ${a.name ?: "track"}",
                                        thumbnailUrl = "",
                                        downloadUrl = a.url,
                                        sizeBytes = asize
                                    )
                                }

                                // Subtitle renditions: EXT-X-MEDIA TYPE=SUBTITLES
                                val subs: List<SubtitleRendition> = parseSubtitleRenditions(master, url)
                                Log.d("HLS", "Found ${subs.size} subtitle renditions")
                                if (subs.isEmpty()) {
                                    val mediaLines = master.lines().filter { it.contains("EXT-X-MEDIA", ignoreCase = true) }
                                    Log.d("HLS", "EXT-X-MEDIA lines: ${mediaLines.size}")
                                }
                                val subItems: List<VideoItem> = subs.mapIndexed { sidx: Int, s: SubtitleRendition ->
                                    VideoItem(
                                        id = (_uiState.value.videos.size + videoItems.size + audioItems.size + sidx + 1).toString(),
                                        title = "SUB ${s.name ?: s.lang ?: "subtitle"}",
                                        thumbnailUrl = "",
                                        downloadUrl = s.url,
                                        sizeBytes = null
                                    )
                                }

                                // CLOSED-CAPTIONS (embedded) – indirilebilir URI olmayabilir; bilgi amaçlı göster
                                val ccs: List<ClosedCaptionsRendition> = parseClosedCaptionsRenditions(master)
                                Log.d("HLS", "Found ${ccs.size} closed captions renditions")
                                val ccItems: List<VideoItem> = ccs.mapIndexed { cidx: Int, c: ClosedCaptionsRendition ->
                                    VideoItem(
                                        id = (_uiState.value.videos.size + videoItems.size + audioItems.size + subItems.size + cidx + 1).toString(),
                                        title = "CC ${c.name ?: c.lang ?: c.instreamId ?: "embedded"}",
                                        thumbnailUrl = "",
                                        downloadUrl = "cc://embedded",
                                        sizeBytes = null
                                    )
                                }

                                (videoItems + audioItems + subItems + ccItems)
                            } else {
                                // Media playlist doğrudan
                                val size = estimateHlsSize(url)
                                listOf(
                                    VideoItem(
                                        id = (_uiState.value.videos.size + 1).toString(),
                                        title = "HLS",
                                        thumbnailUrl = "",
                                        downloadUrl = url,
                                        sizeBytes = size
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("HLS", "Error processing m3u8 $url: ${e.message}", e)
                            // Hata durumunda detaylı başlıkla item oluştur
                            val base = buildTitleFromUrl(url, null)
                            val reason = e.message ?: "unknown"
                            listOf(VideoItem(
                                id = (_uiState.value.videos.size + 1).toString(),
                                title = "$base (Parse Error: $reason)",
                                thumbnailUrl = "",
                                downloadUrl = url,
                                sizeBytes = null
                            ))
                        }
                    }
                    else -> emptyList()
                }
            }

            _uiState.value = _uiState.value.copy(
                videos = _uiState.value.videos + items
            )
        }
    }

    private data class AudioRendition(val name: String?, val url: String)
    private data class SubtitleRendition(val name: String?, val lang: String?, val url: String)
    private data class ClosedCaptionsRendition(val name: String?, val lang: String?, val instreamId: String?)
    
    private fun formatBandwidth(bandwidth: Long): String {
        return when {
            bandwidth >= 1_000_000 -> "${bandwidth / 1_000_000}M"
            bandwidth >= 1_000 -> "${bandwidth / 1_000}K"
            else -> "${bandwidth}bps"
        }
    }

    private fun parseAudioRenditions(master: String, baseUrl: String): List<AudioRendition> {
        val list = mutableListOf<AudioRendition>()
        val lines = master.lines()
        lines.forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) return@forEach
            if (!hasType(line, "AUDIO")) return@forEach
            val uri = parseAttr(line, "URI")
            val name = parseAttr(line, "NAME")
            if (uri != null) list.add(AudioRendition(name, resolveUrl(baseUrl, uri)))
        }
        if (list.isEmpty()) {
            val mediaLines = lines.filter { it.contains("EXT-X-MEDIA", ignoreCase = true) }
            Log.d("HLS", "MEDIA lines (audio scan): ${mediaLines.size}")
        }
        return list
    }

    private fun parseSubtitleRenditions(master: String, baseUrl: String): List<SubtitleRendition> {
        val list = mutableListOf<SubtitleRendition>()
        val lines = master.lines()
        lines.forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) return@forEach
            if (!hasType(line, "SUBTITLES")) return@forEach
            val uri = parseAttr(line, "URI")
            val name = parseAttr(line, "NAME")
            val lang = parseAttr(line, "LANGUAGE") ?: parseAttr(line, "LANG")
            if (uri != null) list.add(SubtitleRendition(name, lang, resolveUrl(baseUrl, uri)))
        }
        if (list.isEmpty()) {
            val mediaLines = lines.filter { it.contains("EXT-X-MEDIA", ignoreCase = true) }
            Log.d("HLS", "MEDIA lines (subs scan): ${mediaLines.size}")
            mediaLines.take(10).forEach { Log.d("HLS", "MEDIA: ${it}") }
        }
        return list
    }

    private fun parseClosedCaptionsRenditions(master: String): List<ClosedCaptionsRendition> {
        val list = mutableListOf<ClosedCaptionsRendition>()
        master.lines().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) return@forEach
            if (!hasType(line, "CLOSED-CAPTIONS")) return@forEach
            val name = parseAttr(line, "NAME")
            val lang = parseAttr(line, "LANGUAGE")
            val instream = parseAttr(line, "INSTREAM-ID")
            list.add(ClosedCaptionsRendition(name, lang, instream))
        }
        return list
    }

    // HLS attribute parser: supports NAME="x" | NAME='x' | NAME=x (until comma)
    private fun parseAttr(attrsLine: String, key: String): String? {
        val pattern = "(?i)\\b" + Regex.escape(key) + "\\b=\\\"([^\\\"]*)\\\"|" +
                "(?i)\\b" + Regex.escape(key) + "\\b='([^']*)'|" +
                "(?i)\\b" + Regex.escape(key) + "\\b=([^,]*)"
        val regex = Regex(pattern)
        val m = regex.find(attrsLine) ?: return null
        val v = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
        return v.trim().trim('"', '\'')
    }

    private fun hasType(attrsLine: String, type: String): Boolean {
        val pattern = "(?i)\\bTYPE\\s*=\\s*\\\"?" + Regex.escape(type) + "\\\"?\\b"
        return Regex(pattern).containsMatchIn(attrsLine)
    }

    // HLS/VTT altyazı indirme: media playlist ise segmentleri sırayla indirip metin olarak birleştir, plain .vtt ise direkt indir
    private fun startSubtitleDownload(subUrl: String, baseName: String): Boolean {
        return try {
            val text = fetchText(subUrl)
            val isPlaylist = text.lines().any { it.trimStart().startsWith("#EXTM3U", ignoreCase = true) }
            val outDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir
            val outVtt = File(outDir, "$baseName.vtt")
            if (!isPlaylist && (subUrl.lowercase().endsWith(".vtt") || text.contains("WEBVTT"))) {
                // Düz VTT
                FileOutputStream(outVtt).use { it.write(text.toByteArray()) }
            } else {
                // HLS subtitle playlist: segmentleri topla ve metin olarak birleştir
                val mediaUrl = subUrl
                val segs = parseSegmentUrls(mediaUrl, text)
                if (segs.isEmpty()) throw IllegalStateException("Subtitle segments not found")
                Log.d("HLS", "Subtitle playlist: ${segs.size} segments")
                val prevRef = currentReferer
                try {
                    currentReferer = mediaUrl
                    FileOutputStream(outVtt, false).use { fos ->
                        segs.forEachIndexed { idx, sUrl ->
                            Log.d("HLS", "Subtitle segment downloading: ${idx + 1}/${segs.size}")
                            val part = fetchText(sUrl)
                            // Fazla başlıkları temizle (WEBVTT/BOM). İlk segmentte başlığı koru.
                            val normalized = if (idx == 0) part else part.replaceFirst("\uFEFF", "").replaceFirst(Regex("^\\s*WEBVTT\\s*\n"), "")
                            fos.write(normalized.toByteArray())
                            fos.write('\n'.code)
                        }
                    }
                } finally {
                    currentReferer = prevRef
                }
            }
            // Public Downloads'a kopyala
            val exported = exportToPublicDownloads(outVtt, "$baseName.vtt", "text/vtt")
            if (exported != null) Log.i("HLS", "Subtitle exported to public: $exported")
            true
        } catch (e: Exception) {
            Log.w("HLS", "Subtitle download failed", e)
            false
        }
    }

    private fun isLikelyAdUrl(lowerUrl: String): Boolean {
        val blockedHints = listOf(
            "doubleclick",
            "googlesyndication",
            "adservice",
            "adsystem",
            "tracking",
            "analytics",
            "pubads",
            "/ads/",
            "adserver"
        )
        return blockedHints.any { lowerUrl.contains(it) }
    }
}
