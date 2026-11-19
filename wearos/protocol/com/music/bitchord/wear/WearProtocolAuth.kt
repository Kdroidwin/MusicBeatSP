package com.music.bitchord.wear

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** HMAC framing and encrypted local storage shared by the phone and Wear APKs. */
object WearProtocolAuth {
    private const val PREFS = "wear_protocol_auth"
    private const val AES_ALIAS = "musicbeat.wear-auth.v1"
    private const val MAGIC = 0x4d425731 // MBW1
    private const val IV_BYTES = 12
    private const val NONCE_BYTES = 16
    private const val MAC_BYTES = 32
    private const val HEADER_BYTES = 4 + 8 + NONCE_BYTES + 4
    private const val MAX_BODY_BYTES = 64 * 1024
    private const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L
    private val random = SecureRandom()

    data class Verified(val body: ByteArray, val nonce: String)

    /** Generates and persists a phone-side random key, or reads the existing one. */
    @Synchronized
    fun getOrCreate(context: Context): ByteArray {
        load(context)?.let { return it }
        val value = ByteArray(32).also(random::nextBytes)
        try {
            store(context, value)
        } catch (error: Exception) {
            value.fill(0)
            throw error
        }
        return value
    }

    /** Replaces the current installation key; callers should provision the returned key to paired watches. */
    @Synchronized
    fun rotate(context: Context): ByteArray {
        val value = ByteArray(32).also(random::nextBytes)
        try {
            store(context, value)
        } catch (error: Exception) {
            value.fill(0)
            throw error
        }
        return value
    }

    /** Stores a provisioned key encrypted with a non-exportable Android Keystore key. */
    @Synchronized
    fun store(context: Context, value: ByteArray) {
        require(value.size == 32)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, localKey(context), GCMParameterSpec(128, iv))
        cipher.updateAAD(context.packageName.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value)
        try {
            check(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString("value", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit(),
            ) { "Could not persist the Wear authentication key" }
        } finally {
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    fun load(context: Context): ByteArray? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP)
        val encrypted = Base64.decode(prefs.getString("value", null), Base64.NO_WRAP)
        require(iv.size == IV_BYTES && encrypted.size == 48)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, localKey(context), GCMParameterSpec(128, iv))
        cipher.updateAAD(context.packageName.toByteArray(Charsets.UTF_8))
        cipher.doFinal(encrypted).also {
            iv.fill(0)
            encrypted.fill(0)
            require(it.size == 32)
        }
    }.getOrNull()

    fun encode(key: ByteArray, purpose: String, body: ByteArray): ByteArray {
        require(key.size == 32 && purpose.length <= 128 && body.size <= MAX_BODY_BYTES)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .putInt(MAGIC)
            .putLong(System.currentTimeMillis())
            .put(nonce)
            .putInt(body.size)
            .array()
        val mac = hmac(key, purpose, header, body)
        return ByteBuffer.allocate(header.size + body.size + mac.size)
            .put(header).put(body).put(mac).array().also {
                nonce.fill(0)
                mac.fill(0)
            }
    }

    fun verify(key: ByteArray, purpose: String, packet: ByteArray): Verified? = runCatching {
        require(key.size == 32 && purpose.length <= 128)
        require(packet.size in (HEADER_BYTES + MAC_BYTES)..(HEADER_BYTES + MAX_BODY_BYTES + MAC_BYTES))
        val buffer = ByteBuffer.wrap(packet)
        require(buffer.int == MAGIC)
        val timestamp = buffer.long
        require(kotlin.math.abs(System.currentTimeMillis() - timestamp) <= MAX_CLOCK_SKEW_MS)
        val nonceBytes = ByteArray(NONCE_BYTES).also(buffer::get)
        val bodyLength = buffer.int
        require(bodyLength in 0..MAX_BODY_BYTES)
        require(packet.size == HEADER_BYTES + bodyLength + MAC_BYTES)
        val body = ByteArray(bodyLength).also(buffer::get)
        val receivedMac = ByteArray(MAC_BYTES).also(buffer::get)
        val header = packet.copyOfRange(0, HEADER_BYTES)
        val expectedMac = hmac(key, purpose, header, body)
        require(MessageDigest.isEqual(receivedMac, expectedMac))
        Verified(body, nonceBytes.joinToString("") { "%02x".format(it) }).also {
            nonceBytes.fill(0)
            receivedMac.fill(0)
            expectedMac.fill(0)
        }
    }.getOrNull()

    private fun hmac(key: ByteArray, purpose: String, header: ByteArray, body: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(purpose.toByteArray(Charsets.UTF_8))
            update(byteArrayOf(0))
            update(header)
            doFinal(body)
        }

    private fun localKey(context: Context): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(AES_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }
}
