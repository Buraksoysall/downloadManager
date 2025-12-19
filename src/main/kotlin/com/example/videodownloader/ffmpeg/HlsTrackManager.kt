package com.example.videodownloader.ffmpeg

/**
 * HLS Track Manager for separate video/audio/subtitle download and merge
 * 
 * Usage example:
 * ```kotlin
 * val root = context.getExternalFilesDir(null)
 * val videoPath = "$root/video.mp4"
 * val audioPath = "$root/audio.aac"
 * val subtitlePath = "$root/subtitle.vtt"
 * val outputPath = "$root/final.mp4"
 * 
 * // Download individual tracks
 * val vPath = HlsTrackManager.downloadTrack(context, videoUrl, "video.mp4", headers)
 * val aPath = HlsTrackManager.downloadTrack(context, audioUrl, "audio.aac", headers)
 * val sPath = HlsTrackManager.downloadTrack(context, subtitleUrl, "subtitle.vtt", headers)
 * 
 * // Merge all tracks
 * val merged = HlsTrackManager.mergeMedia(context, vPath, aPath, sPath, outputPath, headers)
 * 
 * // Or download all and merge automatically
 * HlsTrackManager.downloadAllAndMerge(context, videoUrl, audioUrl, subtitleUrl, headers, callback)
 * ```
 */

import android.content.Context
import android.net.Uri
import android.util.Log
// FFmpegKit imports removed for Play Store compliance (GPL license conflict)
// import com.arthenica.ffmpegkit.FFmpegKit
// import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

object HlsTrackManager {

    data class SubtitleInfo(
        val url: String,
        val language: String?,
        val name: String?,
        val groupId: String?
    )

    data class VariantInfo(
        val playlistUrl: String,
        val subtitlesGroupId: String?
    )

    data class EmbeddedSubtitleProbe(
        val hasSubtitleStream: Boolean,
        val streamCount: Int,
        val rawLog: String
    )

    data class Headers(
        val userAgent: String? = null,
        val referer: String? = null,
        val cookie: String? = null
    )

    interface DownloadCallback {
        fun onProgress(track: String, percent: Int)
        fun onTrackCompleted(track: String, path: String)
        fun onAllCompleted(videoPath: String, audioPath: String, subtitlePath: String?, mergedPath: String)
        fun onError(message: String, throwable: Throwable? = null)
    }

    private const val HTTP_LOGGING = true

    private fun loggingInterceptor(tag: String = "HttpLog"): Interceptor = Interceptor { chain ->
        val req = chain.request()
        val start = System.nanoTime()
        if (HTTP_LOGGING) {
            android.util.Log.d(tag, "--> ${'$'}{req.method} ${'$'}{req.url}")
            try {
                req.headers.names().forEach { name ->
                    req.headers.values(name).forEach { value -> android.util.Log.v(tag, "${'$'}name: ${'$'}value") }
                }
            } catch (_: Throwable) {}
        }
        val resp = chain.proceed(req)
        val tookMs = (System.nanoTime() - start) / 1_000_000
        if (HTTP_LOGGING) {
            val peek = kotlin.runCatching { resp.peekBody(64 * 1024).string() }.getOrNull()
            android.util.Log.d(tag, "<-- ${'$'}{resp.code} ${'$'}{resp.message} (${tookMs}ms) ${'$'}{req.url}")
            if (!peek.isNullOrEmpty()) android.util.Log.v(tag, "body: ${peek.take(2000)}")
        }
        resp
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor("HlsHTTP"))
            .build()
    }


    private fun headersString(headers: Headers?): String {
        if (headers == null) return ""
        fun originOf(url: String?): String? = try {
            if (url.isNullOrBlank()) null else URL(url).let { u ->
                val port = if (u.port != -1 && u.port != 80 && u.port != 443) ":${u.port}" else ""
                "${u.protocol}://${u.host}$port"
            }
        } catch (_: Exception) { null }
        val parts = mutableListOf<String>()
        headers.userAgent?.let { parts.add("User-Agent: $it") }
        headers.referer?.let { parts.add("Referer: $it") }
        originOf(headers.referer)?.let { parts.add("Origin: $it") }
        parts.add("Accept: application/x-mpegURL,application/vnd.apple.mpegurl,*/*")
        parts.add("Accept-Language: tr-TR,tr;q=0.8,en-US;q=0.6,en;q=0.4")
        headers.cookie?.let { parts.add("Cookie: $it") }
        return parts.joinToString(separator = "\r\n")
    }

    private fun resolve(base: String, relative: String): String {
        return try {
            URL(URL(base), relative).toString()
        } catch (_: Throwable) {
            relative
        }
    }

    // Manifest içinde harici altyazı var mı? Variant listesi ile birlikte döner
    suspend fun manifestCheck(masterUrl: String, headers: Headers?): Pair<List<SubtitleInfo>, List<VariantInfo>> = withContext(Dispatchers.IO) {
        val TAG = "HlsSubtitle"
        val body = readText(masterUrl, headers)
        android.util.Log.i(TAG, "===== ManifestCheck =====")
        android.util.Log.i(TAG, "URL: $masterUrl")
        val externalSubtitles = mutableListOf<SubtitleInfo>()
        val variants = mutableListOf<VariantInfo>()

        var lastSubGroup: String? = null
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-MEDIA", true) && line.contains("TYPE=SUBTITLES", true)) {
                fun attr(key: String): String? {
                    val r = Regex("(?i)\\b" + key + "\\b=\\\"([^\\\"]*)\\\"|(?i)\\b" + key + "\\b=([^,]*)")
                    val m = r.find(line) ?: return null
                    val v = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
                    return v.trim().trim('"', '\'')
                }
                val uri = attr("URI")
                val name = attr("NAME")
                val lang = attr("LANGUAGE")
                val group = attr("GROUP-ID")
                if (!uri.isNullOrBlank()) {
                    val abs = resolve(masterUrl, uri)
                    externalSubtitles += SubtitleInfo(abs, lang, name, group)
                }
            }
            if (line.startsWith("#EXT-X-STREAM-INF", true)) {
                val m = Regex("(?i)SUBTITLES=\\\"([^\\\"]*)\\\"").find(line)
                lastSubGroup = m?.groupValues?.getOrNull(1)
                continue
            }
            if (line.isNotEmpty() && !line.startsWith("#") && lastSubGroup != null) {
                val abs = resolve(masterUrl, line)
                variants += VariantInfo(abs, lastSubGroup)
                lastSubGroup = null
            }
        }

        if (externalSubtitles.isNotEmpty()) {
            android.util.Log.i(TAG, "Manifest subtitles found (${externalSubtitles.size})")
            externalSubtitles.forEachIndexed { i, s ->
                android.util.Log.i(TAG, "SUB[$i]: ${s.language}/${s.name} -> ${s.url} (group=${s.groupId})")
            }
            android.util.Log.i(TAG, "🎉 Altyazı bulundu (manifest içinde)")
        } else {
            android.util.Log.w(TAG, "Manifest'te harici altyazı bulunamadı")
            android.util.Log.w(TAG, "❌ Altyazı tespit edilemedi (manifest içinde)")
        }
        // Eğer master değilse (EXT-X-STREAM-INF yok), bu URL muhtemelen media playlist'tir.
        if (variants.isEmpty()) {
            val isMaster = body.lines().any { it.trimStart().startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
            if (!isMaster) {
                // Fallback: verilen URL'i variant playlist olarak ele al
                variants += VariantInfo(masterUrl, null)
                android.util.Log.i(TAG, "Master değil; media playlist olarak ele alındı. Variant fallback eklendi")
            }
        }
        android.util.Log.i(TAG, "Variant count: ${variants.size}")
        return@withContext externalSubtitles to variants
    }

    // Variant playlistten ilk N segmenti indirip FFmpegKit ile altyazı stream var mı tespit et
    suspend fun segmentCheck(context: Context, variantPlaylistUrl: String, headers: Headers?, maxSegments: Int = 5): EmbeddedSubtitleProbe = withContext(Dispatchers.IO) {
        val TAG = "HlsSubtitle"
        val playlist = readText(variantPlaylistUrl, headers)
        android.util.Log.i(TAG, "===== FFmpegCheck (segment) =====")
        android.util.Log.i(TAG, "Variant playlist: $variantPlaylistUrl")

        val allSegs = playlist.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        android.util.Log.i(TAG, "Playlist segment count: ${allSegs.size}")
        val pickIndices = if (allSegs.isEmpty()) emptyList() else buildList {
            val n = kotlin.math.min(maxSegments, allSegs.size)
            if (n == allSegs.size) {
                addAll((0 until n).toList())
            } else {
                // Örnekleme: baş, 25%, 50%, 75%, son ... eşit aralıklı
                for (i in 0 until n) {
                    val idx = ((i.toDouble() / (n - 1).coerceAtLeast(1)) * (allSegs.size - 1)).toInt()
                    add(idx)
                }
            }
        }
        val segUrls = pickIndices.map { resolve(variantPlaylistUrl, allSegs[it]) }

        if (segUrls.isEmpty()) {
            android.util.Log.w(TAG, "Segment bulunamadı")
            android.util.Log.w(TAG, "❌ Altyazı tespit edilemedi (segment seçilemedi)")
            return@withContext EmbeddedSubtitleProbe(false, 0, "")
        }

        var anyLogs = ""
        for ((idx, segUrl) in segUrls.withIndex()) {
            android.util.Log.d(TAG, "FFmpegCheck: segment ${idx + 1}/${segUrls.size} -> $segUrl")
            val tmp = File.createTempFile("seg_", ".ts", context.cacheDir).apply { deleteOnExit() }
            downloadBinary(segUrl, tmp, headers)
            val cmd = "-hide_banner -protocol_whitelist file,crypto,tcp,https,tls,subfile,pipe,tee,concat -i \"${tmp.absolutePath}\" -f null -"
            // FFmpegKit removed for Play Store compliance - subtitle detection disabled
            // val ss = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
            // val logs = ss.allLogsAsString
            // anyLogs = logs
            // val match = Regex("""Stream #\d+:\d+.*: Subtitle""").findAll(logs).toList()
            // val hasSub = match.isNotEmpty() ||
            //         logs.contains("eia_608", true) ||
            //         logs.contains("EIA-608", true) ||
            //         logs.contains("Closed Captions", true)
            // val count = match.size
            android.util.Log.i(TAG, "FFmpegAnalyze(seg ${idx + 1}): Subtitle detection disabled (FFmpeg removed)")
            // if (hasSub) {
            //     android.util.Log.i(TAG, "🎉 Altyazı bulundu (segment içinde)")
            //     android.util.Log.d(TAG, "FFmpeg raw:\n${logs.take(4000)}")
            //     return@withContext EmbeddedSubtitleProbe(true, count, logs)
            // }
        }
        android.util.Log.w(TAG, "❌ Altyazı tespit edilemedi (segment içinde)")
        android.util.Log.d(TAG, "FFmpeg raw(last):\n${anyLogs.take(4000)}")
        return@withContext EmbeddedSubtitleProbe(false, 0, anyLogs)
    }

    // HLS içinde gömülü altyazıyı SRT olarak çıkar (-map 0:s:0)
    suspend fun extractEmbeddedSubtitleFromHls(context: Context, hlsUrl: String, headers: Headers?, outSrt: File): Boolean = withContext(Dispatchers.IO) {
        val TAG = "HlsSubtitle"
        val headerBlock = headersString(headers)
        val cmd = listOf(
            "-y",
            "-protocol_whitelist file,crypto,tcp,https,tls,subfile,pipe,tee,concat",
            if (headerBlock.isNotEmpty()) "-headers \"$headerBlock\r\n\"" else "",
            "-i \"$hlsUrl\"",
            "-map 0:s:0",
            "-c:s srt",
            "\"${outSrt.absolutePath}\""
        ).filter { it.isNotEmpty() }.joinToString(" ")

        android.util.Log.i(TAG, "===== SubtitleExtract (FFmpeg DISABLED) =====")
        android.util.Log.i(TAG, "Input: $hlsUrl")
        android.util.Log.i(TAG, "Out: ${outSrt.absolutePath}")
        // FFmpegKit removed for Play Store compliance
        // val ss = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        val ok = false // Disabled - FFmpeg functionality removed
        if (ok) {
            android.util.Log.i(TAG, "🎉 Altyazı bulundu ve çıkarıldı: ${outSrt.absolutePath}")
        } else {
            android.util.Log.w(TAG, "❌ Altyazı çıkarma devre dışı (FFmpeg removed for Play Store)")
            // android.util.Log.d(TAG, ss.allLogsAsString.take(4000))
            android.util.Log.w(TAG, "Fallback: External subtitle tracks will be used instead")
        }
        return@withContext ok
    }

    // Master ya da media playlist'i yerel klasöre indir, segment URL'lerini yerel dosyalara çevirerek kısmi bir .m3u8 oluştur
    private suspend fun downloadHlsToLocalForSubtitle(context: Context, masterUrl: String, headers: Headers?, maxSegments: Int = 200): File = withContext(Dispatchers.IO) {
        val TAG = "HlsSubtitle"
        val cacheDir = File(context.cacheDir, "hls_sub_${System.currentTimeMillis()}")
        cacheDir.mkdirs()
        val masterBody = readText(masterUrl, headers)
        val isMaster = masterBody.lines().any { it.trimStart().startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
        val mediaUrl = if (isMaster) {
            // Bir varyant seç: basitçe ilk #EXT-X-STREAM-INF sonrası gelen URI
            var chosen: String? = null
            val lines = masterBody.lines()
            var pickNext = false
            for (raw in lines) {
                val t = raw.trim()
                if (t.startsWith("#EXT-X-STREAM-INF", true)) { pickNext = true; continue }
                if (pickNext && t.isNotEmpty() && !t.startsWith("#")) { chosen = resolve(masterUrl, t); break }
            }
            chosen ?: masterUrl
        } else masterUrl

        val mediaBody = readText(mediaUrl, headers)
        val allSegs = mediaBody.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val n = kotlin.math.min(maxSegments, allSegs.size)
        val pick = if (n == allSegs.size) (0 until n).toList() else (0 until n).map { idx -> ((idx.toDouble()/(n-1).coerceAtLeast(1))*(allSegs.size-1)).toInt() }
        val segUrls = pick.map { resolve(mediaUrl, allSegs[it]) }

        // init-map varsa indir
        val initMapUrl = run {
            val line = mediaBody.lines().firstOrNull { it.startsWith("#EXT-X-MAP:") }
            val uriRaw = line?.let { Regex("URI=\"([^\"]+)\"").find(it)?.groupValues?.getOrNull(1) }
            uriRaw?.let { resolve(mediaUrl, it) }
        }

        val localPlaylist = File(cacheDir, "local.m3u8")
        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#EXT-X-VERSION:3")
        // EXT-X-TARGETDURATION tahmini (opsiyonel)
        mediaBody.lines().firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }?.let { sb.appendLine(it) }
        // DISCONTINUITY-SEQUENCE vb. kopyalanabilir
        mediaBody.lines().filter { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") || it.startsWith("#EXT-X-PLAYLIST-TYPE:") } .forEach { sb.appendLine(it) }

        if (initMapUrl != null) {
            val mapFile = File(cacheDir, "init.mp4")
            downloadBinary(initMapUrl, mapFile, headers)
            sb.appendLine("#EXT-X-MAP:URI=\"${mapFile.absolutePath}\"")
        }

        // EXTINF süreleri yoksa yaklaşık 4.0 kullan
        val extinfLines = mediaBody.lines().filter { it.startsWith("#EXTINF:") }
        val defaultInf = extinfLines.firstOrNull() ?: "#EXTINF:4.0,"
        for ((i, su) in segUrls.withIndex()) {
            val segFile = File(cacheDir, "seg_${String.format("%05d", i)}.ts")
            downloadBinary(su, segFile, headers)
            val inf = mediaBody.lines().getOrNull(mediaBody.lines().indexOfFirst { it.contains(su.substringAfterLast('/')) } - 1)
            sb.appendLine(inf ?: defaultInf)
            sb.appendLine(segFile.absolutePath)
        }
        // Sonlandırma
        if (mediaBody.lines().any { it.startsWith("#EXT-X-ENDLIST") }) sb.appendLine("#EXT-X-ENDLIST")
        localPlaylist.writeText(sb.toString())
        android.util.Log.i(TAG, "Local HLS for subtitle created: ${localPlaylist.absolutePath}")
        return@withContext localPlaylist
    }

    // Yerel oluşturulmuş .m3u8 üzerinden SRT çıkarmayı dene (SSL/sertifika sorunlarını aşmak için)
    private suspend fun extractEmbeddedSubtitleFromLocal(context: Context, localPlaylist: File, outSrt: File): Boolean = withContext(Dispatchers.IO) {
        val TAG = "HlsSubtitle"
        val cmd = listOf(
            "-y",
            "-protocol_whitelist file,crypto,tcp,https,tls,subfile,pipe,tee,concat",
            "-i \"${localPlaylist.absolutePath}\"",
            "-map 0:s:0",
            "-c:s srt",
            "\"${outSrt.absolutePath}\""
        ).joinToString(" ")
        android.util.Log.i(TAG, "===== SubtitleExtract (FFmpeg LOCAL - DISABLED) =====")
        // FFmpegKit removed for Play Store compliance
        // val ss = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd)
        val ok = false // Disabled - FFmpeg functionality removed
        if (ok) android.util.Log.i(TAG, "🎉 Altyazı bulundu ve çıkarıldı (LOCAL): ${outSrt.absolutePath}")
        else android.util.Log.w(TAG, "❌ Altyazı çıkarma devre dışı (FFmpeg removed for Play Store)")
        return@withContext ok
    }

    // Harici URL yoksa otomatik altyazı tespit/indirme/çıkarma
    suspend fun findAndFetchSubtitle(context: Context, masterUrl: String, headers: Headers?): String? = withContext(Dispatchers.IO) {
        val TAG = "HlsSubtitle"
        return@withContext runCatching {
            val (subs, variants) = manifestCheck(masterUrl, headers)
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            if (subs.isNotEmpty()) {
                val first = subs.first()
                val out = File(root, "subtitle_manifest.vtt")
                downloadVttPlaylistToSingleFile(first.url, out, headers)
                exportToPublicDownloads(context, out, out.name, "text/vtt")
                return@runCatching out.absolutePath
            }
            // Variant(ler) + masterUrl üzerinde birkaç segment deneyerek probe et
            val candidates = buildList {
                addAll(variants.map { it.playlistUrl })
                if (isEmpty()) add(masterUrl)
                else add(masterUrl) // master'ı da sonuna ekle
            }
            var probe: EmbeddedSubtitleProbe? = null
            for ((i, u) in candidates.withIndex()) {
                android.util.Log.i(TAG, "Probe candidate ${i + 1}/${candidates.size}: $u")
                val p = segmentCheck(context, u, headers, maxSegments = 5)
                if (p.hasSubtitleStream) { probe = p; break }
            }
            val finalProbe = probe ?: return@runCatching null
            if (!probe.hasSubtitleStream) return@runCatching null
            val outSrt = File(root, "subtitle_extracted.srt")
            if (outSrt.exists()) outSrt.delete()
            val okRemote = extractEmbeddedSubtitleFromHls(context, masterUrl, headers, outSrt)
            if (okRemote) {
                exportToPublicDownloads(context, outSrt, outSrt.name, "application/x-subrip")
                outSrt.absolutePath
            } else {
                // Remote başarısızsa: yerel kopya üzerinden dene
                val localM3u8 = downloadHlsToLocalForSubtitle(context, masterUrl, headers, maxSegments = 200)
                val okLocal = extractEmbeddedSubtitleFromLocal(context, localM3u8, outSrt)
                if (okLocal) {
                    exportToPublicDownloads(context, outSrt, outSrt.name, "application/x-subrip")
                    outSrt.absolutePath
                } else null
            }
        }.getOrNull()
    }

    private suspend fun downloadBinary(url: String, out: File, headers: Headers?) = withContext(Dispatchers.IO) {
        out.parentFile?.mkdirs()
        val rb = Request.Builder().url(url)
        headers?.userAgent?.let { rb.header("User-Agent", it) }
        headers?.referer?.let { rb.header("Referer", it) }
        headers?.cookie?.let { rb.header("Cookie", it) }
        rb.header("Accept", "*/*")
        http.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url")
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        fos.write(buf, 0, n)
                    }
                }
            } ?: throw IllegalStateException("Empty body for $url")
        }
    }

    private suspend fun readText(url: String, headers: Headers?): String = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url)
        headers?.userAgent?.let { rb.header("User-Agent", it) }
        headers?.referer?.let { rb.header("Referer", it) }
        headers?.cookie?.let { rb.header("Cookie", it) }
        rb.header("Accept", "application/x-mpegURL,application/vnd.apple.mpegurl,*/*")
        http.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url")
            return@withContext resp.body?.string() ?: ""
        }
    }

    private fun isVtt(text: String): Boolean {
        val head = text.trimStart().take(256)
        return head.startsWith("WEBVTT", ignoreCase = true) || (head.contains("-->") && !head.contains("#EXTM3U"))
    }

    private fun isM3u8(text: String): Boolean {
        return text.trimStart().startsWith("#EXTM3U")
    }

    private fun isVttPlaylist(master: String): Boolean {
        if (!isM3u8(master)) return false
        return master.lines().any { line ->
            val t = line.trim()
            t.isNotEmpty() && !t.startsWith("#") && (t.endsWith(".vtt", true) || t.contains(".vtt", true))
        }
    }

    private suspend fun downloadVttPlaylistToSingleFile(playlistUrl: String, outFile: File, headers: Headers?) {
        val m3u = readText(playlistUrl, headers)
        if (!isM3u8(m3u)) {
            if (isVtt(m3u)) {
                outFile.parentFile?.mkdirs()
                outFile.writeText(m3u)
                return
            }
            downloadBinary(playlistUrl, outFile, headers)
            return
        }
        val segs = m3u.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { resolve(playlistUrl, it) }
        if (segs.isEmpty()) throw IllegalStateException("No VTT segments in playlist")
        val tempDir = File(outFile.parentFile, ".vtt_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val parts = mutableListOf<File>()
        try {
            for ((i, u) in segs.withIndex()) {
                val f = File(tempDir, "part_${String.format("%05d", i)}.vtt")
                downloadBinary(u, f, headers)
                parts.add(f)
            }
            FileOutputStream(outFile, false).use { fos ->
                for (p in parts) {
                    p.inputStream().use { input ->
                        val buf = ByteArray(32 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            fos.write(buf, 0, n)
                        }
                    }
                }
            }
        } finally {
            parts.forEach { it.delete() }
            tempDir.deleteRecursively()
        }
    }

    private data class KeyInfo(val method: String, val uri: String, val ivHex: String?)

    private fun parseInitMap(baseUrl: String, media: String): String? {
        val line = media.lines().firstOrNull { it.startsWith("#EXT-X-MAP:") } ?: return null
        val uriRaw = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.getOrNull(1) ?: return null
        return resolve(baseUrl, uriRaw)
    }

    private fun parseKeyInfo(baseUrl: String, media: String): KeyInfo? {
        val line = media.lines().firstOrNull { it.startsWith("#EXT-X-KEY:") } ?: return null
        val method = Regex("METHOD=([^,]+)").find(line)?.groupValues?.getOrNull(1)
        val uriRaw = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.getOrNull(1)
        val iv = Regex("IV=0x([0-9A-Fa-f]+)").find(line)?.groupValues?.getOrNull(1)
        if (method.isNullOrBlank() || uriRaw.isNullOrBlank()) return null
        return KeyInfo(method, resolve(baseUrl, uriRaw), iv)
    }

    private data class Segment(val url: String, val rangeStart: Long? = null, val rangeLen: Long? = null)

    private data class MediaTask(val segment: Segment, val keyInfo: KeyInfo?)

    private fun parseMediaPlan(baseUrl: String, media: String): Pair<String?, List<MediaTask>> {
        var currentKey: KeyInfo? = null
        var lastUri: String? = null
        var pendingRangeLen: Long? = null
        var pendingRangeStart: Long? = null
        var initMap: String? = parseInitMap(baseUrl, media)
        val tasks = ArrayList<MediaTask>()

        val lines = media.lines()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()
            if (line.startsWith("#EXT-X-KEY:")) {
                currentKey = parseKeyInfo(baseUrl, line)
                i++
                continue
            }
            if (line.startsWith("#EXT-X-MAP:")) {
                // override init map if encountered mid-playlist
                initMap = parseInitMap(baseUrl, line)
                i++
                continue
            }
            if (line.startsWith("#EXT-X-BYTERANGE:")) {
                val v = line.substringAfter(":", "").trim()
                val parts = v.split("@")
                pendingRangeLen = parts.getOrNull(0)?.toLongOrNull()
                pendingRangeStart = parts.getOrNull(1)?.toLongOrNull()
                i++
                continue
            }
            if (line.isNotEmpty() && !line.startsWith("#")) {
                val abs = resolve(baseUrl, line)
                lastUri = abs
                val seg = if (pendingRangeLen != null) {
                    // if start not provided, it means continue from previous end -> we'll still request exact range len with - for servers that require explicit ranges
                    val start = pendingRangeStart
                    Segment(abs, start, pendingRangeLen)
                } else {
                    Segment(abs)
                }
                tasks.add(MediaTask(seg, currentKey))
                // reset pending range unless RFC implies reuse of uri without new uri line; handled by next iterations as BYTERANGE followed by same uri or another BYTERANGE
                pendingRangeLen = null
                pendingRangeStart = null
            }
            i++
        }
        return initMap to tasks
    }

    private data class Variant(val url: String, val bandwidth: Long)

    private fun isMasterPlaylist(body: String): Boolean {
        return body.lines().any { it.trimStart().startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
    }

    private fun parseVariants(master: String, baseUrl: String): List<Variant> {
        val result = mutableListOf<Variant>()
        val lines = master.lines()
        var bw: Long? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val m = Regex("BANDWIDTH=(\\d+)").find(line)
                bw = m?.groupValues?.getOrNull(1)?.toLongOrNull()
                var j = i + 1
                while (j < lines.size) {
                    val u = lines[j].trim()
                    if (u.isNotEmpty() && !u.startsWith("#")) {
                        val abs = resolve(baseUrl, u)
                        if (bw != null) result.add(Variant(abs, bw!!))
                        break
                    }
                    j++
                }
                i = j
            }
            i++
        }
        return result
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

    private suspend fun downloadDecryptedSegment(url: String, headers: Headers?, keyInfo: KeyInfo?, segIndex: Int, rangeStart: Long? = null, rangeLen: Long? = null): ByteArray {
        val rb = Request.Builder().url(url)
        headers?.userAgent?.let { rb.header("User-Agent", it) }
        headers?.referer?.let { rb.header("Referer", it) }
        headers?.cookie?.let { rb.header("Cookie", it) }
        rb.header("Accept", "*/*")
        if (rangeLen != null) {
            val end = if (rangeStart != null) (rangeStart + rangeLen - 1) else (rangeLen - 1)
            val header = if (rangeStart != null) "bytes=${'$'}rangeStart-${'$'}end" else "bytes=0-${'$'}end"
            rb.header("Range", header)
        }
        http.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url")
            val bodyBytes = resp.body?.bytes() ?: throw IllegalStateException("Empty body for segment")
            if (keyInfo == null || keyInfo.method != "AES-128") return bodyBytes
            // fetch key
            val keyReq = Request.Builder().url(keyInfo.uri).get()
            headers?.userAgent?.let { keyReq.header("User-Agent", it) }
            headers?.referer?.let { keyReq.header("Referer", it) }
            headers?.cookie?.let { keyReq.header("Cookie", it) }
            http.newCall(keyReq.build()).execute().use { keyResp ->
                if (!keyResp.isSuccessful) throw IllegalStateException("Key HTTP ${keyResp.code}")
                val keyBytes = keyResp.body?.bytes() ?: throw IllegalStateException("Empty key body")
                val ivBytes = keyInfo.ivHex?.let { hexToBytes(it) } ?: sequenceIv(segIndex)
                return decryptAes128(bodyBytes, keyBytes, ivBytes)
            }
        }
    }

    private fun exportToPublicDownloads(context: Context, src: File, displayName: String, mime: String) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        java.io.FileInputStream(src).use { inp -> inp.copyTo(out) }
                    }
                    values.clear()
                    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } else {
                val dm = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                dm.addCompletedDownload(displayName, displayName, true, mime, src.absolutePath, src.length(), true)
            }
        } catch (_: Throwable) {}
    }

    suspend fun downloadTrack(
        context: Context,
        url: String,
        fileName: String,
        headers: Headers? = null,
        onSegmentProgress: ((downloaded: Int, total: Int) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val out = File(root, fileName)
        val lower = url.lowercase()

        if (lower.endsWith(".vtt")) {
            downloadBinary(url, out, headers)
            return@withContext out.absolutePath
        }

        runCatching {
            val head = readText(url, headers).take(256)
            if (isVtt(head)) {
                out.parentFile?.mkdirs()
                out.writeText(readText(url, headers))
                return@withContext out.absolutePath
            }
        }

        runCatching {
            val body = readText(url, headers)
            if (isVttPlaylist(body)) {
                downloadVttPlaylistToSingleFile(url, out, headers)
                exportToPublicDownloads(context, out, out.name, "text/vtt")
                return@withContext out.absolutePath
            }
        }

        if (!lower.contains(".m3u8")) {
            downloadBinary(url, out, headers)
            val mime = when (out.extension.lowercase()) { "aac" -> "audio/aac"; "mp4" -> "video/mp4"; else -> "application/octet-stream" }
            exportToPublicDownloads(context, out, out.name, mime)
            return@withContext out.absolutePath
        }
        // HLS master/media handling
        var playlistUrl = url
        val body = readText(url, headers)
        if (!isM3u8(body)) throw IllegalStateException("Invalid HLS playlist")
        if (isMasterPlaylist(body)) {
            val variants = parseVariants(body, url)
            val chosen = variants.maxByOrNull { it.bandwidth }?.url ?: variants.firstOrNull()?.url
            if (chosen != null) {
                Log.d("HLS", "Chosen variant bw=${variants.maxByOrNull { it.bandwidth }?.bandwidth} url=$chosen")
                playlistUrl = chosen
            }
        }
        val media = if (playlistUrl == url) body else readText(playlistUrl, headers)
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()

        val (initMap, plan) = parseMediaPlan(playlistUrl, media)
        if (plan.isEmpty()) throw IllegalStateException("No HLS segments")

        FileOutputStream(out, true).use { fos ->
            // write init (for fMP4)
            if (initMap != null) {
                val initBytes = downloadDecryptedSegment(initMap, headers, null, 0)
                fos.write(initBytes)
            }
            // write media segments
            plan.forEachIndexed { idx, task ->
                val seg = task.segment
                val bytes = downloadDecryptedSegment(seg.url, headers, task.keyInfo, idx, seg.rangeStart, seg.rangeLen)
                fos.write(bytes)
                onSegmentProgress?.invoke(idx + 1, plan.size)
            }
        }
        exportToPublicDownloads(context, out, out.name, "video/mp4")
        return@withContext out.absolutePath
    }

    suspend fun mergeMedia(
        context: Context,
        videoPath: String,
        audioPath: String,
        subtitlePath: String?,
        outputPath: String,
        headers: Headers? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val out = File(outputPath.ifBlank { File(root, "final.mp4").absolutePath })
        out.parentFile?.mkdirs()

        val hasSub = !subtitlePath.isNullOrBlank() && File(subtitlePath).exists()
        val inputs = mutableListOf<String>()
        inputs.add("-i ${q(videoPath)}")
        inputs.add("-i ${q(audioPath)}")
        if (hasSub) inputs.add("-i ${q(subtitlePath!!)}")

        val maps = mutableListOf<String>()
        maps.add("-map 0:v:0")
        maps.add("-map 1:a")  // İlk audio stream'i otomatik seç
        if (hasSub) maps.add("-map 2:0")

        val codecs = mutableListOf<String>()
        codecs.add("-c:v copy")
        codecs.add("-c:a copy")  // Önce copy dene, sorun olursa aac'ye çevir
        if (hasSub) codecs.add("-c:s mov_text")

        val cmd = listOf(
            "-y",
            "-protocol_whitelist file,crypto,tcp,https,tls,subfile,pipe,tee,concat",
            inputs.joinToString(" "),
            maps.joinToString(" "),
            codecs.joinToString(" "),
            "-movflags +faststart",
            q(out.absolutePath)
        ).joinToString(" ")

        android.util.Log.i("HLS_MERGE", "🔧 ===== FFmpeg Merge Command =====")
        android.util.Log.i("HLS_MERGE", "📹 Video: $videoPath")
        android.util.Log.i("HLS_MERGE", "📹 Video exists: ${File(videoPath).exists()}, size: ${File(videoPath).length()} bytes")
        android.util.Log.i("HLS_MERGE", "🎵 Audio: $audioPath")
        android.util.Log.i("HLS_MERGE", "🎵 Audio exists: ${File(audioPath).exists()}, size: ${File(audioPath).length()} bytes")
        if (hasSub) {
            android.util.Log.i("HLS_MERGE", "📝 Subtitle: $subtitlePath")
            android.util.Log.i("HLS_MERGE", "📝 Subtitle exists: ${File(subtitlePath!!).exists()}, size: ${File(subtitlePath).length()} bytes")
        }
        android.util.Log.i("HLS_MERGE", "⚡ FFmpeg Command: $cmd")

        // FFmpegKit removed for Play Store compliance
        // val ss = FFmpegKit.execute(cmd)
        android.util.Log.i("HLS_MERGE", "🔧 FFmpeg execution DISABLED (removed for Play Store)")
        // android.util.Log.i("HLS_MERGE", "🔧 Return code: ${ss.returnCode}")
        
        val mergeSuccess = false // Disabled - FFmpeg functionality removed
        if (!mergeSuccess) {
            android.util.Log.w("HLS_MERGE", "⚠️ FFmpeg merge disabled (removed for Play Store compliance)")
            // android.util.Log.w("HLS_MERGE", "⚠️ Error logs: ${ss.allLogsAsString}")
            
            // Fallback: Audio'yu AAC'ye çevir
            val codecsAac = mutableListOf<String>()
            codecsAac.add("-c:v copy")
            codecsAac.add("-c:a aac")  // AAC'ye çevir
            if (hasSub) codecsAac.add("-c:s mov_text")
            
            val cmdAac = listOf(
                "-y",
                "-protocol_whitelist file,crypto,tcp,https,tls,subfile,pipe,tee,concat",
                inputs.joinToString(" "),
                maps.joinToString(" "),
                codecsAac.joinToString(" "),
                "-movflags +faststart",
                q(out.absolutePath)
            ).joinToString(" ")
            
            android.util.Log.i("HLS_MERGE", "🔄 Fallback FFmpeg Command DISABLED: $cmdAac")
            // FFmpegKit removed for Play Store compliance
            // val ss2 = FFmpegKit.execute(cmdAac)
            android.util.Log.i("HLS_MERGE", "🔧 Fallback execution DISABLED (FFmpeg removed)")
            // android.util.Log.i("HLS_MERGE", "🔧 Fallback return code: ${ss2.returnCode}")
            
            // if (!ReturnCode.isSuccess(ss2.returnCode)) {
            //     android.util.Log.e("HLS_MERGE", "❌ FFmpeg merge failed even with AAC!")
            //     android.util.Log.e("HLS_MERGE", "❌ Fallback error logs: ${ss2.allLogsAsString}")
            //     throw IllegalStateException("FFmpeg merge failed: ${ss2.returnCode}\n${ss2.allLogsAsString}")
            // }
            
            // Return false since FFmpeg is disabled
            return@withContext false
        }
        
        // Çıktı dosyasının durumunu kontrol et - FFmpeg disabled
        android.util.Log.e("HLS_MERGE", "❌ Merge functionality disabled (FFmpeg removed for Play Store)")
        
        return@withContext false // FFmpeg disabled - merge not available
    }

    suspend fun downloadAllAndMerge(
        context: Context,
        videoUrl: String,
        audioUrl: String,
        subtitleUrl: String?,
        headers: Headers?,
        callback: DownloadCallback
    ) {
        try {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            callback.onProgress("video", 0)
            val videoPath = downloadTrack(context, videoUrl, "video.mp4", headers) { d, t ->
                val pct = ((d * 100f) / t).toInt()
                callback.onProgress("video", pct)
            }
            callback.onTrackCompleted("video", videoPath)
            callback.onProgress("audio", 0)
            val audioPath = downloadTrack(context, audioUrl, "audio.aac", headers) { d, t ->
                val pct = ((d * 100f) / t).toInt()
                callback.onProgress("audio", pct)
            }
            callback.onTrackCompleted("audio", audioPath)
            
            var subtitlePath: String? = null
            if (!subtitleUrl.isNullOrBlank()) {
                callback.onProgress("subtitle", 0)
                subtitlePath = downloadTrack(context, subtitleUrl, "subtitle.vtt", headers) { d, t ->
                    val pct = ((d * 100f) / t).toInt()
                    callback.onProgress("subtitle", pct)
                }
                callback.onTrackCompleted("subtitle", subtitlePath)
            } else {
                // Otomatik altyazı tespiti (manifest/segment + FFmpeg)
                val detected = findAndFetchSubtitle(context, videoUrl, headers)
                if (!detected.isNullOrBlank()) {
                    subtitlePath = detected
                    callback.onTrackCompleted("subtitle", subtitlePath!!)
                }
            }

            val outputPath = File(root, "final.mp4").absolutePath
            val merged = mergeMedia(context, videoPath, audioPath, subtitlePath, outputPath, headers)
            runCatching { exportToPublicDownloads(context, File(videoPath), File(videoPath).name, "video/mp4") }
            runCatching { exportToPublicDownloads(context, File(audioPath), File(audioPath).name, "audio/aac") }
            if (subtitlePath != null) runCatching { exportToPublicDownloads(context, File(subtitlePath), File(subtitlePath).name, "text/vtt") }
            // Merge disabled - FFmpeg removed for Play Store compliance
            callback.onAllCompleted(videoPath, audioPath, subtitlePath, if (merged) "merged" else "merge_failed")
        } catch (t: Throwable) {
            callback.onError(t.message ?: "Unknown error", t)
        }
    }

    private fun q(s: String): String = "\"${s.replace("\"", "\\\"")}\""
}
