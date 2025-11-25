package com.example.videodownloader.exoplayer

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ExoPlayerMergerKtx {
    private const val TAG = "EXO_MERGER"
    
    data class SelectedTracks(
        val videoFormat: Format? = null,
        val audioFormat: Format? = null,
        val subtitleFormat: Format? = null
    )

    fun getSelectedTracks(player: ExoPlayer): SelectedTracks {
        val currentTracks = player.currentTracks
        var selectedAudioFormat: Format? = null
        var selectedSubtitleFormat: Format? = null
        var selectedVideoFormat: Format? = null

        for (group in currentTracks.groups) {
            if (group.isSelected) {
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> selectedVideoFormat = group.mediaTrackGroup.getFormat(0)
                    C.TRACK_TYPE_AUDIO -> selectedAudioFormat = group.mediaTrackGroup.getFormat(0)
                    C.TRACK_TYPE_TEXT -> selectedSubtitleFormat = group.mediaTrackGroup.getFormat(0)
                }
            }
        }

        return SelectedTracks(
            videoFormat = selectedVideoFormat,
            audioFormat = selectedAudioFormat,
            subtitleFormat = selectedSubtitleFormat
        )
    }

    data class HlsSelection(val mediaUrl: String, val subtitleUrl: String?)

    // Sadece doğru HLS media playlist URL'sini (variant) döndürür
    suspend fun chooseEffectiveHlsUrl(
        context: Context,
        hlsUrl: String,
        preferDub: Boolean = false,
        preferSubs: Boolean = false,
        userAgent: String? = null,
        referer: String? = null,
        cookies: String? = null
    ): String = withContext(Dispatchers.IO) {
        val requestProps = HashMap<String, String>()
        referer?.let { requestProps["Referer"] = it }
        cookies?.let { requestProps["Cookie"] = it }
        referer?.let {
            runCatching {
                val u = java.net.URI(it)
                val origin = if (u.port != -1 && u.port != 80 && u.port != 443) "${u.scheme}://${u.host}:${u.port}" else "${u.scheme}://${u.host}"
                requestProps["Origin"] = origin
            }
        }
        requestProps["Accept-Language"] = "tr-TR,tr;q=0.8,en-US;q=0.6,en;q=0.4"
        requestProps["Accept"] = "application/x-mpegURL,application/vnd.apple.mpegurl,*/*"

        // Preflight: master mi media mı?
        val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
        val rb = Request.Builder().url(hlsUrl)
        userAgent?.let { rb.header("User-Agent", it) }
        requestProps.forEach { (k, v) -> if (!v.isNullOrEmpty()) rb.header(k, v) }
        rb.header("Accept", requestProps["Accept"] ?: "*/*")
        client.newCall(rb.build()).execute().use { resp ->
            val code = resp.code
            val bodyStr = resp.body?.string() ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code on HLS URL")
            // Master playlist içeriyorsa variant seç
            return@withContext if (bodyStr.contains("#EXT-X-STREAM-INF")) {
                val variants = parseMasterVariants(bodyStr, hlsUrl)
                val chosen = chooseVariantByPreference(variants, preferDub, preferSubs)
                chosen ?: variants.filter { !it.hasSeparateAudio }.maxByOrNull { it.bandwidth }?.url
                ?: variants.maxByOrNull { it.bandwidth }?.url ?: hlsUrl
            } else {
                // Zaten media playlist
                hlsUrl
            }
        }
    }

    // Media + Subtitle URL (varsa) döndürür
    suspend fun chooseEffectiveHlsAndSubtitle(
        context: Context,
        hlsUrl: String,
        preferDub: Boolean = false,
        preferSubs: Boolean = true,
        preferLang: String = "tr",
        userAgent: String? = null,
        referer: String? = null,
        cookies: String? = null
    ): HlsSelection = withContext(Dispatchers.IO) {
        val requestProps = HashMap<String, String>()
        referer?.let { requestProps["Referer"] = it }
        cookies?.let { requestProps["Cookie"] = it }
        referer?.let {
            runCatching {
                val u = java.net.URI(it)
                val origin = if (u.port != -1 && u.port != 80 && u.port != 443) "${u.scheme}://${u.host}:${u.port}" else "${u.scheme}://${u.host}"
                requestProps["Origin"] = origin
            }
        }
        requestProps["Accept-Language"] = "tr-TR,tr;q=0.8,en-US;q=0.6,en;q=0.4"
        requestProps["Accept"] = "application/x-mpegURL,application/vnd.apple.mpegurl,*/*"

        val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
        val rb = Request.Builder().url(hlsUrl)
        userAgent?.let { rb.header("User-Agent", it) }
        requestProps.forEach { (k, v) -> if (!v.isNullOrEmpty()) rb.header(k, v) }
        rb.header("Accept", requestProps["Accept"] ?: "*/*")
        client.newCall(rb.build()).execute().use { resp ->
            val code = resp.code
            val bodyStr = resp.body?.string() ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code on HLS URL")
            if (bodyStr.contains("#EXT-X-STREAM-INF")) {
                val variants = parseMasterVariants(bodyStr, hlsUrl)
                // Log variants
                runCatching {
                    variants.forEach { v ->
                        Log.d(TAG, "VARIANT bw=${v.bandwidth} separateAudio=${v.hasSeparateAudio} info='${v.info.take(120)}' url=${v.url}")
                    }
                }
                val chosenUrl = chooseVariantByPreference(variants, preferDub, preferSubs)
                    ?: variants.filter { !it.hasSeparateAudio }.maxByOrNull { it.bandwidth }?.url
                    ?: variants.maxByOrNull { it.bandwidth }?.url
                    ?: hlsUrl
                Log.d(TAG, "Chosen variant: $chosenUrl")
                val subs = parseSubtitleGroups(bodyStr, hlsUrl)
                val subUrl = if (preferSubs && subs.isNotEmpty()) {
                    val u = chooseSubtitleUrl(subs, preferLang)
                    Log.d(TAG, "Chosen subtitle: $u")
                    u
                } else null
                return@use HlsSelection(chosenUrl, subUrl)
            } else {
                return@use HlsSelection(hlsUrl, null)
            }
        }
    }

    suspend fun downloadAndMergeSelectedTracks(
        context: Context,
        hlsUrl: String,
        outputPath: String,
        selectedTracks: SelectedTracks,
        transformer: Transformer
    ): Boolean {
        try {
            // Create MediaItem from HLS URL
            val mediaItem = MediaItem.fromUri(hlsUrl)
            
            // Create EditedMediaItem with selected tracks
            // Always keep video, optionally keep audio based on selection
            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(selectedTracks.audioFormat == null)
                .setRemoveVideo(false) // Always keep video
                .build()

            // Transformer is provided (configured with headerized MediaSourceFactory)

            // Export with suspendCancellableCoroutine on Main thread
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            Log.i(TAG, "Transformer completed. output=$outputPath")
                            continuation.resume(true)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            Log.e(TAG, "Transformer error: ${exportException.message}", exportException)
                            continuation.resumeWithException(exportException)
                        }
                    }

                    transformer.addListener(listener)
                    
                    try {
                        Log.d(TAG, "Transformer starting export -> $outputPath")
                        transformer.start(editedMediaItem, outputPath)
                        
                        continuation.invokeOnCancellation {
                            Log.w(TAG, "Transformer cancelled")
                            transformer.cancel()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Transformer start failed: ${e.message}", e)
                        continuation.resumeWithException(e)
                    }
                }
            }
        } catch (e: Exception) {
            throw e
        }
        return true
    }

    suspend fun downloadHlsWithPlayer(
        context: Context,
        hlsUrl: String,
        outputPath: String,
        preferDub: Boolean = false,
        preferSubs: Boolean = false,
        userAgent: String? = null,
        referer: String? = null,
        cookies: String? = null
    ): Boolean {
        return try {
            // ExoPlayer operations must be on Main thread
            var effectiveUrl: String = hlsUrl
            lateinit var mediaSourceFactory: DefaultMediaSourceFactory
            val selectedTracks = withContext(Dispatchers.Main) {
                // Build HTTP data source with headers
                val requestProps = HashMap<String, String>()
                userAgent?.let { /* DefaultHttpDataSource has setUserAgent separately */ }
                referer?.let { requestProps["Referer"] = it }
                cookies?.let { requestProps["Cookie"] = it }
                // Derive Origin from referer if available
                referer?.let {
                    try {
                        val u = java.net.URI(it)
                        val origin = if (u.port != -1 && u.port != 80 && u.port != 443) "${u.scheme}://${u.host}:${u.port}" else "${u.scheme}://${u.host}"
                        requestProps["Origin"] = origin
                    } catch (_: Exception) {}
                }
                requestProps["Accept-Language"] = "tr-TR,tr;q=0.8,en-US;q=0.6,en;q=0.4"
                requestProps["Accept"] = "application/x-mpegURL,application/vnd.apple.mpegurl,*/*"

                // Log headers summary (mask cookies)
                runCatching {
                    val masked = HashMap(requestProps)
                    masked["Cookie"] = if (requestProps.containsKey("Cookie")) "<masked>" else null
                    Log.d(TAG, "Preparing player for URL=$hlsUrl UA=${userAgent ?: "-"} headers=$masked")
                }

                val httpFactory = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(25000)
                if (!userAgent.isNullOrBlank()) {
                    httpFactory.setUserAgent(userAgent)
                }
                if (requestProps.isNotEmpty()) {
                    httpFactory.setDefaultRequestProperties(requestProps)
                }
                mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)

                val player = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build()
                
                try {
                    var preflightIsHls = false
                    // Preflight probe with OkHttp using same headers (run on IO thread)
                    val preflightResult = withContext(Dispatchers.IO) {
                        val client = OkHttpClient.Builder()
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .build()
                        val rb = Request.Builder().url(hlsUrl)
                        userAgent?.let { rb.header("User-Agent", it) }
                        requestProps.forEach { (k, v) -> if (!v.isNullOrEmpty()) rb.header(k, v) }
                        rb.header("Accept", requestProps["Accept"] ?: "*/*")
                        val resp = client.newCall(rb.build()).execute()
                        val code = resp.code
                        val ctype = resp.header("Content-Type") ?: ""
                        val peek = resp.body?.source()?.peek()?.readUtf8Line() ?: ""
                        Log.d(TAG, "Preflight GET code=$code ctype=$ctype firstLine='${peek.trim()}'")
                        if (code !in 200..299) {
                            throw IllegalStateException("HTTP $code on HLS URL")
                        }
                        if (peek.startsWith("#EXTM3U")) preflightIsHls = true
                        resp.close()
                        preflightIsHls
                    }
                    // If this is a master playlist, try to choose best variant
                    effectiveUrl = hlsUrl
                    if (preflightResult) {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
                                val rb = Request.Builder().url(hlsUrl)
                                userAgent?.let { rb.header("User-Agent", it) }
                                requestProps.forEach { (k, v) -> if (!v.isNullOrEmpty()) rb.header(k, v) }
                                rb.header("Accept", requestProps["Accept"] ?: "*/*")
                                client.newCall(rb.build()).execute().use { resp ->
                                    val body = resp.body?.string() ?: ""
                                    if (body.contains("#EXT-X-STREAM-INF")) {
                                        val variants = parseMasterVariants(body, hlsUrl)
                                        val chosen = chooseVariantByPreference(variants, preferDub, preferSubs)
                                        if (chosen != null) {
                                            effectiveUrl = chosen
                                            Log.d(TAG, "Chosen variant: $effectiveUrl")
                                        } else if (variants.isNotEmpty()) {
                                            effectiveUrl = variants.maxByOrNull { it.bandwidth }!!.url
                                            Log.d(TAG, "Fallback highest bandwidth: $effectiveUrl")
                                        }
                                    }
                                }
                            }.onFailure { e -> Log.w(TAG, "Variant selection failed: ${e.message}") }
                        }
                    }

                    // Prepare media; force HLS mime only if URL hints .m3u8 or preflight says M3U8
                    val isHls = effectiveUrl.contains(".m3u8", ignoreCase = true) || preflightResult
                    val mib = MediaItem.Builder().setUri(effectiveUrl)
                    if (isHls) mib.setMimeType(MimeTypes.APPLICATION_M3U8)
                    val mediaItem = mib.build()
                    Log.d(TAG, "Setting mediaItem. isHls=$isHls uri=${mediaItem.localConfiguration?.uri}")
                    // Let DefaultMediaSourceFactory handle HLS via mime type
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    Log.d(TAG, "Player.prepare() called, waiting for READY...")

                    // Wait for READY or error with timeout
                    var waited = 0
                    while (player.playbackState != ExoPlayer.STATE_READY && waited < 100) {
                        player.playerError?.let {
                            // Try to unwrap InvalidResponseCodeException for diagnostics
                            val cause = it.cause
                            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                                val uri = cause.dataSpec?.uri
                                val headers = cause.headerFields
                                if (headers != null) {
                                    val masked = mutableMapOf<String, List<String>>()
                                    for ((hk, hv) in headers) {
                                        masked[hk] = if (hk.equals("set-cookie", true)) listOf("<masked>") else hv
                                    }
                                    Log.e(TAG, "HTTP ${cause.responseCode} for $uri headers=$masked")
                                } else {
                                    Log.e(TAG, "HTTP ${cause.responseCode} for $uri (no headers)")
                                }
                            }
                            Log.e(TAG, "Player error during prepare: ${it.message}", it)
                            throw it
                        }
                        delay(100)
                        waited++
                    }

                    // Final check
                    player.playerError?.let {
                        val cause = it.cause
                        if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                            val uri = cause.dataSpec?.uri
                            val headers = cause.headerFields
                            if (headers != null) {
                                val masked = mutableMapOf<String, List<String>>()
                                for ((hk, hv) in headers) {
                                    masked[hk] = if (hk.equals("set-cookie", true)) listOf("<masked>") else hv
                                }
                                Log.e(TAG, "HTTP ${cause.responseCode} for $uri headers=$masked")
                            } else {
                                Log.e(TAG, "HTTP ${cause.responseCode} for $uri (no headers)")
                            }
                        }
                        Log.e(TAG, "Player error after wait: ${it.message}", it)
                        throw it
                    }
                    if (player.playbackState != ExoPlayer.STATE_READY) {
                        Log.e(TAG, "Player not READY after timeout. state=${player.playbackState}")
                        throw IllegalStateException("Player failed to load media - possibly network/format error")
                    }
                    Log.d(TAG, "Player is READY. Current tracks count=${player.currentTracks.groups.size}")

                    // Get available tracks and select preferred ones
                    val tracks = player.currentTracks
                    selectPreferredTracks(tracks, preferDub, preferSubs)
                
                } finally {
                    Log.d(TAG, "Releasing player")
                    player.release()
                }
            }
            
            // Transformer operations can be on IO thread
            withContext(Dispatchers.IO) {
                // Build default Transformer (compile-safe). If export 404 persists, we'll wire a custom asset loader next.
                val transformer = Transformer.Builder(context).build()
                // Use the same effectiveUrl used for playback
                downloadAndMergeSelectedTracks(context, /* hlsUrl = */ effectiveUrl, outputPath, selectedTracks, transformer)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "downloadHlsWithPlayer failed: ${e.message}", e)
            throw e
        }
    }

    private fun selectPreferredTracks(
        tracks: Tracks,
        preferDub: Boolean,
        preferSubs: Boolean
    ): SelectedTracks {
        var bestVideo: Format? = null
        var bestAudio: Format? = null
        var bestSubtitle: Format? = null

        for (group in tracks.groups) {
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    // Always select highest quality video
                    for (i in 0 until group.length) {
                        val format = group.mediaTrackGroup.getFormat(i)
                        if (bestVideo == null || (format.bitrate > bestVideo.bitrate)) {
                            bestVideo = format
                        }
                    }
                }
                C.TRACK_TYPE_AUDIO -> {
                    // Always select some audio track
                    for (i in 0 until group.length) {
                        val format = group.mediaTrackGroup.getFormat(i)
                        val lang = format.language?.lowercase()
                        
                        if (preferDub && (lang == "tr" || lang == "tur" || 
                            format.label?.lowercase()?.contains("dublaj") == true ||
                            format.label?.lowercase()?.contains("türkçe") == true)) {
                            bestAudio = format
                            break
                        }
                        
                        // Fallback to any audio track
                        if (bestAudio == null) {
                            bestAudio = format
                        }
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    if (preferSubs) {
                        // Look for Turkish subtitles
                        for (i in 0 until group.length) {
                            val format = group.mediaTrackGroup.getFormat(i)
                            val lang = format.language?.lowercase()
                            if (lang == "tr" || lang == "tur" ||
                                format.label?.lowercase()?.contains("türkçe") == true) {
                                bestSubtitle = format
                                break
                            }
                        }
                        // Fallback to first subtitle if no Turkish found
                        if (bestSubtitle == null && group.length > 0) {
                            bestSubtitle = group.mediaTrackGroup.getFormat(0)
                        }
                    }
                }
            }
        }

        return SelectedTracks(bestVideo, bestAudio, bestSubtitle)
    }

    private fun applyTrackSelections(player: ExoPlayer, selectedTracks: SelectedTracks) {
        // This is a simplified version - in real implementation you'd use TrackSelectionParameters
        // For now, we'll rely on the Transformer to handle track selection during export
    }

    // --- HLS Master helpers ---
    private data class Variant(val url: String, val bandwidth: Long, val info: String, val hasSeparateAudio: Boolean)
    private data class Subtitle(val url: String, val lang: String?, val name: String?)

    private fun parseMasterVariants(master: String, baseUrl: String): List<Variant> {
        val out = mutableListOf<Variant>()
        val lines = master.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val bw = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val audioAttr = Regex("AUDIO=([^,]+)", RegexOption.IGNORE_CASE).containsMatchIn(line)
                val codecs = Regex("CODECS=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)?.lowercase()
                val codecsHasAudio = codecs?.let { it.contains("mp4a") || it.contains("ac-3") || it.contains("ec-3") || it.contains("aac") } ?: false
                // Separate audio varsayımı: AUDIO attr varsa ve CODECS içinde audio yoksa
                val hasAudio = audioAttr && !codecsHasAudio
                var j = i + 1
                var uriLine: String? = null
                while (j < lines.size) {
                    val l2 = lines[j].trim()
                    if (l2.isNotEmpty() && !l2.startsWith("#")) { uriLine = l2; break }
                    j++
                }
                if (uriLine != null) {
                    val abs = resolveUrl(baseUrl, uriLine)
                    out.add(Variant(abs, bw, line, hasAudio))
                }
                i = j
            }
            i++
        }
        return out
    }

    private fun chooseVariantByPreference(variants: List<Variant>, preferDub: Boolean, preferSubs: Boolean): String? {
        if (variants.isEmpty()) return null
        fun score(v: Variant): Int {
            val l = v.info.lowercase() + " " + v.url.lowercase()
            var s = 0
            if (preferDub) {
                if (listOf("dublaj", "dub", "turk", "türk", "tr").any { l.contains(it) }) s += 5
            }
            if (preferSubs) {
                if (listOf("sub", "subtitle", "vost", "vtt", "srt").any { l.contains(it) }) s += 3
            }
            return s
        }
        // Muxed (no AUDIO=) varyantları önceliklendir
        val muxed = variants.filter { !it.hasSeparateAudio }
        val pool = if (muxed.isNotEmpty()) muxed else variants
        return pool.sortedWith(compareByDescending<Variant> { score(it) }.thenByDescending { it.bandwidth }).first().url
    }

    private fun resolveUrl(base: String, relative: String): String = try {
        URL(URL(base), relative).toString()
    } catch (_: Exception) { relative }

    private fun parseSubtitleGroups(master: String, baseUrl: String): List<Subtitle> {
        val out = mutableListOf<Subtitle>()
        val lines = master.lines()
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("#EXT-X-MEDIA", ignoreCase = true) && t.contains("TYPE=SUBTITLES", true)) {
                val uri = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)
                val lang = Regex("LANGUAGE=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)
                val name = Regex("NAME=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)
                if (uri != null) out.add(Subtitle(resolveUrl(baseUrl, uri), lang, name))
            }
        }
        return out
    }

    private fun chooseSubtitleUrl(subs: List<Subtitle>, preferLang: String): String? {
        if (subs.isEmpty()) return null
        val lang = preferLang.lowercase()
        // Önce dil eşleşmesi, sonra isimde dil/hint, yoksa ilk
        subs.firstOrNull { it.lang?.lowercase() == lang }?.let { return it.url }
        subs.firstOrNull { it.name?.lowercase()?.contains(lang) == true }?.let { return it.url }
        return subs.first().url
    }
}
