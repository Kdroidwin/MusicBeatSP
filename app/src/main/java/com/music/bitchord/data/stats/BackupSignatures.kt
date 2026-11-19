package com.music.bitchord.data.stats

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable backup authentication derived from the password already required for export. */
internal object BackupSignatures {
    private const val ITERATIONS = 240_000
    private const val MIN_ITERATIONS = 120_000
    private const val MAX_ITERATIONS = 350_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    data class Signature(val salt: String, val iterations: Int, val value: String)

    fun sign(payload: ByteArray, password: CharArray): Signature {
        require(password.isNotEmpty()) { "A backup password is required" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val value = authenticate(payload, password, salt, ITERATIONS)
        return Signature(
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            iterations = ITERATIONS,
            value = Base64.encodeToString(value, Base64.NO_WRAP),
        ).also {
            salt.fill(0)
            value.fill(0)
        }
    }

    fun verify(
        expected: String,
        encodedSalt: String,
        iterations: Int,
        payload: ByteArray,
        password: CharArray,
    ): Boolean = runCatching {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS)
        require(password.isNotEmpty())
        val salt = Base64.decode(encodedSalt, Base64.NO_WRAP)
        val signature = Base64.decode(expected, Base64.NO_WRAP)
        require(salt.size == SALT_BYTES && signature.size == KEY_BITS / 8)
        val actual = authenticate(payload, password, salt, iterations)
        try {
            MessageDigest.isEqual(signature, actual)
        } finally {
            salt.fill(0)
            signature.fill(0)
            actual.fill(0)
        }
    }.getOrDefault(false)

    private fun authenticate(payload: ByteArray, password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(payload)
            }
        } finally {
            key.fill(0)
        }
    }
}
