package com.music.bitchord

import com.music.bitchord.feature.lyrics.domain.LyricsExtensionSecurity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsExtensionSecurityTest {
    @Test
    fun extensionIdsCannotEscapeTheirStorageDirectory() {
        assertTrue(LyricsExtensionSecurity.isSafeExtensionId("provider_1.2-test"))
        assertFalse(LyricsExtensionSecurity.isSafeExtensionId("../outside"))
        assertFalse(LyricsExtensionSecurity.isSafeExtensionId("nested/provider"))
        assertFalse(LyricsExtensionSecurity.isSafeExtensionId(""))
        assertFalse(LyricsExtensionSecurity.isSafeExtensionId("/absolute"))
        assertFalse(LyricsExtensionSecurity.isSafeExtensionId("x".repeat(65)))
    }

    @Test
    fun registryAndExtensionFilesRequireCredentialFreeHttpsUrls() {
        assertTrue(LyricsExtensionSecurity.isHttpsUrl("https://example.com/registry.json"))
        assertFalse(LyricsExtensionSecurity.isHttpsUrl("http://example.com/registry.json"))
        assertFalse(LyricsExtensionSecurity.isHttpsUrl("https://user:pass@example.com/script.js"))
        assertFalse(LyricsExtensionSecurity.isHttpsUrl("https:///missing-host/script.js"))
        assertFalse(LyricsExtensionSecurity.isHttpsUrl("https://example.com/script.js#fragment"))
    }
}
