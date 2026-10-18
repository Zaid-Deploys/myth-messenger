package com.myth.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Argon2idTest {

    @Test
    fun testPasswordHashingAndVerification() {
        val password = "SuperSecretPassword123!".toCharArray()
        val hash = Argon2idPasswordHasher.hashPassword(password, iterations = 2, memoryKb = 4096)

        assertTrue("Hash should start with argon2id prefix", hash.startsWith("\$argon2id\$v=19\$"))

        val isCorrect = Argon2idPasswordHasher.verifyPassword(password, hash)
        assertTrue("Verification must succeed with the correct password", isCorrect)

        val wrongPassword = "WrongPassword456!".toCharArray()
        val isWrong = Argon2idPasswordHasher.verifyPassword(wrongPassword, hash)
        assertFalse("Verification must fail with the wrong password", isWrong)
    }

    @Test
    fun testSaltRandomnessProducesDifferentHashes() {
        val password = "IdenticalPassword".toCharArray()
        val hash1 = Argon2idPasswordHasher.hashPassword(password, iterations = 2, memoryKb = 4096)
        val hash2 = Argon2idPasswordHasher.hashPassword(password, iterations = 2, memoryKb = 4096)

        assertTrue(hash1 != hash2)
        assertTrue(Argon2idPasswordHasher.verifyPassword(password, hash1))
        assertTrue(Argon2idPasswordHasher.verifyPassword(password, hash2))
    }
}
