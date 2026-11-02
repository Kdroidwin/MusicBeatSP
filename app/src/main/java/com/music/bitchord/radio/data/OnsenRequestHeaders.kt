package com.music.bitchord.radio.data

/** Request headers used by the public Onsen web API and its HLS CDN. */
internal object OnsenRequestHeaders {
    const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64; rv:158.0) Gecko/20100101 Firefox/158.0"

    val api = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/json; charset=utf-8",
        "X-Client" to "onsen-web",
        "Referer" to "https://www.onsen.ag/",
        "User-Agent" to USER_AGENT,
    )

    val cdn = mapOf(
        "Accept" to "*/*",
        "Origin" to "https://www.onsen.ag",
        "Referer" to "https://www.onsen.ag/",
        "User-Agent" to USER_AGENT,
    )
}
