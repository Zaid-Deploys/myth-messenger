package com.myth.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

object Argon2idPasswordHasher {
    private const val DEFAULT_ITERATIONS = 3
    private const val DEFAULT_MEMORY_KB = 19456 // 19 MiB
    private const val DEFAULT_PARALLELISM = 1
    private const val HASH_LENGTH_BYTES = 32
    private const val SALT_LENGTH_BYTES = 16

    fun hashPassword(
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        memoryKb: Int = DEFAULT_MEMORY_KB,
        parallelism: Int = DEFAULT_PARALLELISM
    ): String {
        val salt = CryptoUtils.randomBytes(SALT_LENGTH_BYTES)
        val hash = ByteArray(HASH_LENGTH_BYTES)

        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKb)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()

        val generator = Argon2BytesGenerator()
        generator.init(params)
        generator.generateBytes(password, hash)

        val saltB64 = CryptoUtils.toBase64(salt)
        val hashB64 = CryptoUtils.toBase64(hash)

        // Clear hash byte array memory
        CryptoUtils.zeroize(hash)

        return "\$argon2id\$v=19\$m=$memoryKb,t=$iterations,p=$parallelism\$$saltB64\$$hashB64"
    }

    fun verifyPassword(password: CharArray, encodedHash: String): Boolean {
        return try {
            val parts = encodedHash.split("$")
            if (parts.size < 5 || parts[1] != "argon2id") {
                return false
            }

            val paramPairs = parts[3].split(",")
            var iterations = DEFAULT_ITERATIONS
            var memoryKb = DEFAULT_MEMORY_KB
            var parallelism = DEFAULT_PARALLELISM

            for (pair in paramPairs) {
                val kv = pair.split("=")
                when (kv[0]) {
                    "m" -> memoryKb = kv[1].toInt()
                    "t" -> iterations = kv[1].toInt()
                    "p" -> parallelism = kv[1].toInt()
                }
            }

            val salt = CryptoUtils.fromBase64(parts[4])
            val expectedHash = CryptoUtils.fromBase64(parts[5])

            val computedHash = ByteArray(expectedHash.size)
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(iterations)
                .withMemoryAsKB(memoryKb)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build()

            val generator = Argon2BytesGenerator()
            generator.init(params)
            generator.generateBytes(password, computedHash)

            val match = CryptoUtils.constantTimeEquals(computedHash, expectedHash)
            CryptoUtils.zeroize(computedHash)
            match
        } catch (e: Exception) {
            false
        }
    }
}
