package com.example.videodownloader.ui

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

data class SubtitleTrackInfo(
    val label: String?,
    val language: String?,
    val mimeType: String?,
    val isSelected: Boolean,
    val url: Uri?
)

object SubtitleInspector {
    private const val TAG = "SubtitleInspector"

    fun installLogging(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                Log.i(TAG, "onTracksChanged() invoked")
                val list = listSubtitleTracksWithUrls(player)
                if (list.isEmpty()) {
                    Log.w(TAG, "No text tracks available.")
                } else {
                    list.forEachIndexed { idx, t ->
                        Log.i(
                            TAG,
                            "Track[$idx] label=${t.label} lang=${t.language} mime=${t.mimeType} selected=${t.isSelected} url=${t.url}"
                        )
                    }
                    val sel = list.firstOrNull { it.isSelected }
                    Log.i(TAG, "Selected subtitle URL: ${sel?.url}")
                }
            }
        })
    }

    fun listSubtitleTracksWithUrls(player: ExoPlayer): List<SubtitleTrackInfo> {
        Log.d(TAG, "Listing subtitle tracks...")
        val tracks = player.currentTracks
        val out = mutableListOf<SubtitleTrackInfo>()

        val sideLoaded = subtitleConfigsFromMediaItem(player.currentMediaItem)
        Log.d(TAG, "Side-loaded subtitle configs: ${sideLoaded.size}")

        val hlsRenditions = subtitleRenditionsByFetchingMaster(player)
        Log.d(TAG, "HLS subtitle renditions discovered: ${hlsRenditions.size}")

        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEach
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val isSelected = group.isTrackSelected(i)
                val url = mapFormatToUrl(format, sideLoaded, hlsRenditions)
                val info = SubtitleTrackInfo(
                    label = format.label,
                    language = format.language,
                    mimeType = format.sampleMimeType,
                    isSelected = isSelected,
                    url = url
                )
                out += info
                Log.d(
                    TAG,
                    "Found text track: label=${format.label} lang=${format.language} mime=${format.sampleMimeType} selected=$isSelected url=$url"
                )
            }
        }
        return out
    }

    fun getSelectedSubtitleUrl(player: ExoPlayer): Uri? {
        val selected = listSubtitleTracksWithUrls(player).firstOrNull { it.isSelected }?.url
        Log.i(TAG, "getSelectedSubtitleUrl() = $selected")
        return selected
    }

    fun fetchText(url: String): String? {
        Log.i(TAG, "Fetching text: $url")
        return runCatching { httpGetWithTimeout(url) }
            .onFailure { Log.e(TAG, "Fetch failed: ${it.message}", it) }
            .getOrNull()
    }

    private fun subtitleConfigsFromMediaItem(item: MediaItem?): List<SubtitleCandidate> {
        val list = mutableListOf<SubtitleCandidate>()
        val cfgs = item?.localConfiguration?.subtitleConfigurations ?: emptyList()
        for (cfg in cfgs) {
            list += SubtitleCandidate(
                url = cfg.uri,
                language = cfg.language,
                name = cfg.label
            )
        }
        return list
    }

    private fun subtitleRenditionsByFetchingMaster(player: ExoPlayer): List<SubtitleCandidate> {
        val masterUri = player.currentMediaItem?.localConfiguration?.uri
        val masterUrl = masterUri?.toString() ?: return emptyList()
        val text = runCatching { httpGetWithTimeout(masterUrl) }
            .onFailure { Log.w(TAG, "Failed to fetch master for subtitles: ${it.message}") }
            .getOrNull() ?: return emptyList()

        val parsed = parseHlsSubtitleRenditions(masterUrl, text)
        Log.d(TAG, "Parsed renditions from master: ${parsed.size}")
        return parsed
    }

    private fun mapFormatToUrl(
        format: Format,
        sideLoaded: List<SubtitleCandidate>,
        hls: List<SubtitleCandidate>
    ): Uri? {
        sideLoaded.firstOrNull { matches(format, it) }?.let {
            Log.v(TAG, "Matched side-loaded by label/lang: ${it.url}")
            return it.url
        }
        hls.firstOrNull { matches(format, it) }?.let {
            Log.v(TAG, "Matched HLS rendition by label/lang: ${it.url}")
            return it.url
        }
        return null
    }

    private fun matches(format: Format, cand: SubtitleCandidate): Boolean {
        val langMatch = !format.language.isNullOrBlank() && format.language.equals(cand.language, ignoreCase = true)
        val labelMatch = !format.label.isNullOrBlank() && !cand.name.isNullOrBlank() &&
                format.label!!.trim().equals(cand.name!!.trim(), ignoreCase = true)
        return langMatch || labelMatch
    }

    private data class SubtitleCandidate(
        val url: Uri?,
        val language: String?,
        val name: String?
    )

    private fun parseHlsSubtitleRenditions(baseUrl: String, manifestText: String): List<SubtitleCandidate> {
        val out = mutableListOf<SubtitleCandidate>()
        manifestText.lineSequence().forEach { ln ->
            if (!ln.startsWith("#EXT-X-MEDIA") || !ln.contains("TYPE=SUBTITLES")) return@forEach
            val attrs = parseAttributeList(ln.substringAfter(":"))
            val uriAttr = attrs["URI"]?.trim('"')
            val nameAttr = attrs["NAME"]?.trim('"')
            val langAttr = attrs["LANGUAGE"]?.trim('"')
            if (uriAttr != null) {
                val abs = safeResolve(baseUrl, uriAttr)
                out += SubtitleCandidate(url = abs, language = langAttr, name = nameAttr)
                Log.v(TAG, "Found rendition: name=$nameAttr lang=$langAttr url=$abs")
            }
        }
        return out
    }

    private fun parseAttributeList(s: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            val eq = s.indexOf('=', startIndex = i)
            if (eq == -1) break
            val key = s.substring(i, eq).trim()
            i = eq + 1
            if (i >= s.length) break
            val value: String
            if (s[i] == '"') {
                val end = s.indexOf('"', startIndex = i + 1)
                if (end == -1) break
                value = s.substring(i, end + 1)
                i = end + 1
            } else {
                val comma = s.indexOf(',', startIndex = i)
                val end = if (comma == -1) s.length else comma
                value = s.substring(i, end).trim()
                i = end
            }
            result[key] = value
            if (i < s.length && s[i] == ',') i++
            while (i < s.length && s[i].isWhitespace()) i++
        }
        return result
    }

    private fun safeResolve(base: String?, relOrAbs: String?): Uri? {
        if (relOrAbs.isNullOrBlank()) return null
        return try {
            if (base.isNullOrBlank()) Uri.parse(relOrAbs)
            else Uri.parse(URI(base).resolve(relOrAbs).toString())
        } catch (_: Throwable) {
            Uri.parse(relOrAbs)
        }
    }

    private fun httpGetWithTimeout(url: String): String {
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(url).get().build()
        val start = System.nanoTime()
        client.newCall(req).execute().use { resp ->
            val durMs = (System.nanoTime() - start) / 1_000_000
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} for $url in ${durMs}ms")
                error("HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            Log.i(TAG, "Fetched ${body.length} chars from $url in ${durMs}ms")
            return body
        }
    }
}
