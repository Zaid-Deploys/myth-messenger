package com.myth.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object CryptoUtils {
    private val secureRandom = SecureRandom()

    fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    fun toBase64(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun fromBase64(str: String): ByteArray {
        return Base64.getDecoder().decode(str.trim())
    }

    /**
     * Constant-time byte array comparison to prevent timing side-channel attacks.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        return MessageDigest.isEqual(a, b)
    }

    /**
     * Wipes byte array memory by zeroing out contents.
     */
    fun zeroize(bytes: ByteArray) {
        bytes.fill(0)
    }
}
