package com.music.bitchord.data.stats

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Encryption helpers for on-device history and portable, password-protected backups. */
internal object ListeningHistoryCrypto {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val LOCAL_KEY_ALIAS = "musicbeat.listening-history.v1"
    private const val LOCAL_MAGIC = "MBLH\u0001"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val BACKUP_KDF_ITERATIONS = 240_000
    private const val MIN_BACKUP_KDF_ITERATIONS = 120_000
    private const val MAX_BACKUP_KDF_ITERATIONS = 350_000
    private const val SALT_BYTES = 16
    private const val MAX_ENCRYPTED_FIELD_CHARS = 180 * 1024 * 1024

    private val secureRandom = SecureRandom()

    fun encryptLocal(month: String, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, localKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(month.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)
        return LOCAL_MAGIC.toByteArray(Charsets.US_ASCII) + iv + ciphertext
    }

    fun decryptLocal(month: String, encrypted: ByteArray): ByteArray {
        val magic = LOCAL_MAGIC.toByteArray(Charsets.US_ASCII)
        require(encrypted.size > magic.size + GCM_IV_BYTES + GCM_TAG_BITS / 8) { "Invalid encrypted history file" }
        require(encrypted.copyOfRange(0, magic.size).contentEquals(magic)) { "Unknown encrypted history format" }
        val iv = encrypted.copyOfRange(magic.size, magic.size + GCM_IV_BYTES)
        val ciphertext = encrypted.copyOfRange(magic.size + GCM_IV_BYTES, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, localKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(month.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext)
    }

    fun encryptForBackup(plaintext: ByteArray, password: CharArray): EncryptedListeningBackup {
        require(password.isNotEmpty()) { "A backup password is required" }
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val key = deriveBackupKey(password, salt, BACKUP_KDF_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(BACKUP_AAD)
        return EncryptedListeningBackup(
            kdfIterations = BACKUP_KDF_ITERATIONS,
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP),
        )
    }

    fun decryptFromBackup(payload: EncryptedListeningBackup, password: CharArray): ByteArray {
        require(password.isNotEmpty()) { "Enter the backup password" }
        require(payload.version == 1) { "Unsupported encrypted history format" }
        require(payload.kdf == "PBKDF2-HMAC-SHA256") { "Unsupported backup key derivation" }
        require(payload.kdfIterations in MIN_BACKUP_KDF_ITERATIONS..MAX_BACKUP_KDF_ITERATIONS) {
            "Invalid backup key derivation settings"
        }
        require(payload.salt.length <= 128 && payload.iv.length <= 128 && payload.ciphertext.length <= MAX_ENCRYPTED_FIELD_CHARS) {
            "Encrypted history data is too large"
        }
        val salt = Base64.decode(payload.salt, Base64.NO_WRAP)
        val iv = Base64.decode(payload.iv, Base64.NO_WRAP)
        val ciphertext = Base64.decode(payload.ciphertext, Base64.NO_WRAP)
        require(salt.size == SALT_BYTES && iv.size == GCM_IV_BYTES && ciphertext.size >= GCM_TAG_BITS / 8) {
            "Invalid encrypted history data"
        }
        val key = deriveBackupKey(password, salt, payload.kdfIterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(BACKUP_AAD)
        return cipher.doFinal(ciphertext)
    }

    private fun localKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (store.getKey(LOCAL_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                LOCAL_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun deriveBackupKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES").also { encoded.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }

    private val BACKUP_AAD = "musicbeat-listening-history-backup-v1".toByteArray(Charsets.UTF_8)
}

@Serializable
data class EncryptedListeningBackup(
    val version: Int = 1,
    val kdf: String = "PBKDF2-HMAC-SHA256",
    val kdfIterations: Int = 240_000,
    val salt: String,
    val iv: String,
    val ciphertext: String,
)
