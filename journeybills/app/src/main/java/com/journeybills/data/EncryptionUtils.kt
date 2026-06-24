package com.journeybills.data

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptionUtils {
    // Hardcoded 16-byte key for app-wide encryption of Drive backups.
    // This ensures any user with the app can decrypt the shared file,
    // but the file is unreadable if opened directly in Google Drive.
    private val keyBytes = byteArrayOf(
        0x5A, 0x3F, 0x11, 0x7E.toByte(), 0x22, 0x4D, 0x6A, 0x1C,
        0x5B, 0x2A, 0x77, 0x6E, 0x41, 0x24, 0x33, 0x55
    )
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"

    fun encrypt(plainText: String): String {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
            
            return Base64.encodeToString(combined, Base64.DEFAULT)
        } catch (e: Exception) {
            e.printStackTrace()
            return plainText
        }
    }

    fun decrypt(encryptedText: String): String {
        try {
            val combined = Base64.decode(encryptedText, Base64.DEFAULT)
            val iv = ByteArray(16)
            System.arraycopy(combined, 0, iv, 0, iv.size)
            val ivSpec = IvParameterSpec(iv)
            
            val encrypted = ByteArray(combined.size - iv.size)
            System.arraycopy(combined, iv.size, encrypted, 0, encrypted.size)
            
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            
            val decrypted = cipher.doFinal(encrypted)
            return String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback in case it's actually unencrypted legacy JSON
            return encryptedText
        }
    }
}
