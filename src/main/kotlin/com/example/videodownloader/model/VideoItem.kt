package com.example.videodownloader.model

data class VideoItem(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val downloadUrl: String,
    val sizeBytes: Long? = null,
    val subtitles: List<SubtitleItem> = emptyList(),
    val hlsMasterUrl: String? = null,
    val hlsAudioTracks: List<AudioTrackInfo> = emptyList(),
    val hlsSubtitleTracks: List<SubtitleTrackInfo> = emptyList()
)

data class AudioTrackInfo(
    val url: String,
    val name: String?,
    val language: String?
)

data class SubtitleTrackInfo(
    val url: String,
    val name: String?,
    val language: String?
)

data class SubtitleItem(
    val url: String,
    val label: String,
    val format: String // vtt, srt, ass, ssa
)
