package com.myth.crypto

import java.security.MessageDigest

object SafetyNumber {
    private const val ITERATIONS = 5200

    /**
     * Computes a deterministic 60-digit safety number for out-of-band verification
     * between two identity keys. Both participants will generate identical numbers
     * regardless of who initiates the comparison.
     */
    fun computeSafetyNumber(
        localIdentityPublicKey: ByteArray,
        remoteIdentityPublicKey: ByteArray
    ): String {
        // Deterministic sorting to ensure symmetry
        val cmp = compareByteArrays(localIdentityPublicKey, remoteIdentityPublicKey)
        val (first, second) = if (cmp <= 0) {
            localIdentityPublicKey to remoteIdentityPublicKey
        } else {
            remoteIdentityPublicKey to localIdentityPublicKey
        }

        val digest = MessageDigest.getInstance("SHA-512")
        digest.update("Myth-Safety-Number-v1".toByteArray(Charsets.UTF_8))
        digest.update(first)
        digest.update(second)
        var hash = digest.digest()

        for (i in 0 until ITERATIONS) {
            digest.reset()
            digest.update(hash)
            digest.update(first)
            hash = digest.digest()
        }

        // Format into 12 blocks of 5 decimal digits (60 digits total)
        val sb = StringBuilder()
        for (i in 0 until 12) {
            val offset = (i * 4) % (hash.size - 4)
            val chunk = ((hash[offset].toInt() and 0xFF) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)
            val positiveChunk = (chunk and 0x7FFFFFFF) % 100000
            val formatted = String.format("%05d", positiveChunk)
            sb.append(formatted)
            if (i < 11) sb.append(" ")
        }
        return sb.toString()
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val byteA = a[i].toInt() and 0xFF
            val byteB = b[i].toInt() and 0xFF
            if (byteA != byteB) {
                return byteA - byteB
            }
        }
        return a.size - b.size
    }
}
