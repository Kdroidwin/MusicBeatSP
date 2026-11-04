package com.music.bitchord.radio.model

enum class RadioService(val key: String) {
    RADIKO("radiko"),
    ONSEN("onsen"),
    RSS("rss");

    companion object {
        fun fromKey(value: String?): RadioService = entries.firstOrNull { it.key == value } ?: RADIKO
    }
}
