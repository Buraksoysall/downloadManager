package com.example.videodownloader.model

data class VideoItem(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val downloadUrl: String,
    val sizeBytes: Long? = null,
    val subtitles: List<SubtitleItem> = emptyList()
)

data class SubtitleItem(
    val url: String,
    val label: String,
    val format: String // vtt, srt, ass, ssa
)
