package com.music.bitchord.radio.model

enum class RadioService(val key: String) {
    RADIKO("radiko"),
    ONSEN("onsen");

    companion object {
        fun fromKey(value: String?): RadioService = entries.firstOrNull { it.key == value } ?: RADIKO
    }
}
