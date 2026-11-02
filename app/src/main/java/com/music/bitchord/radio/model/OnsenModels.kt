package com.music.bitchord.radio.model

data class OnsenProgram(
    val id: String,
    val directoryName: String,
    val title: String,
    val performer: String?,
    val description: String,
    val imageUrl: String?,
    val episodes: List<OnsenEpisode> = emptyList(),
)

data class OnsenEpisode(
    val id: String,
    val title: String,
    val deliveryDate: String?,
    val imageUrl: String?,
    val streamingUrl: String?,
    val isPremium: Boolean,
    val isAudio: Boolean,
)
