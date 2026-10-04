package com.music.bitchord

import com.music.bitchord.feature.localmusic.data.MusicoletBackupParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class MusicoletBackupParserTest {
    @Test
    fun `decrypts playlist entries and metadata`() {
        val encrypted = encrypt(
            """{"S_P":["/Music/first.flac","content://media/song/2"],"S_T":["First","Second"],"S_AL":["Album","Album"],"S_AR":["Artist","Other"],"S_D":[123000,456000]}""",
        )

        val playlist = MusicoletBackupParser.parsePlaylist("My Mix.mpl", encrypted)

        assertEquals("My Mix", playlist?.name)
        assertEquals(2, playlist?.entries?.size)
        assertEquals("First", playlist?.entries?.get(0)?.title)
        assertEquals("Album", playlist?.entries?.get(0)?.album)
        assertEquals("Artist", playlist?.entries?.get(0)?.artist)
        assertEquals(123000L, playlist?.entries?.get(0)?.durationMillis)
    }

    @Test
    fun `accepts a valid empty playlist and ignores non playlist backup members`() {
        val encrypted = encrypt("""{"S_P":[],"S_T":[],"S_AL":[],"S_AR":[],"S_D":[]}""")

        assertTrue(MusicoletBackupParser.parsePlaylist("Empty.mpl", encrypted)?.entries.orEmpty().isEmpty())
        assertNull(MusicoletBackupParser.parsePlaylist("0.favs", encrypted))
        assertNull(MusicoletBackupParser.parsePlaylist("broken.mpl", byteArrayOf(1, 2, 3)))
    }

    private fun encrypt(text: String): ByteArray {
        val cipher = Cipher.getInstance("Blowfish/ECB/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec("JSTMUSIC_2".toByteArray(StandardCharsets.US_ASCII), "Blowfish"),
        )
        return cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
    }
}
