package com.myth.crypto

import org.junit.Assert.assertThrows
import org.junit.Test

class TamperProofTest {

    @Test
    fun testTamperedCiphertextIsRejected() {
        val convId = "conv-sec"
        val masterSecret = CryptoUtils.randomBytes(32)
        val bobDh = X25519Key.generate()

        val alice = DoubleRatchetSession.createAsInitiator(convId, "alice", "bob", masterSecret.copyOf(), bobDh.publicKey)
        val bob = DoubleRatchetSession.createAsRecipient(convId, "bob", "alice", masterSecret.copyOf(), bobDh)

        val plaintext = "Critical secure message".toByteArray(Charsets.UTF_8)
        val enc = alice.encrypt(plaintext)

        // 1. Flip a single bit in the ciphertext
        val tamperedCiphertext = enc.ciphertext.copyOf()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            bob.decrypt(
                ephemeralDhPublicKeyBase64 = enc.ephemeralDhPublicKeyBase64,
                sequenceNumber = enc.sequenceNumber,
                prevChainLen = enc.previousChainLength,
                ciphertext = tamperedCiphertext,
                iv = enc.iv,
                authTag = enc.authTag
            )
        }

        // 2. Flip a bit in the auth tag
        val tamperedTag = enc.authTag.copyOf()
        tamperedTag[0] = (tamperedTag[0].toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            bob.decrypt(
                ephemeralDhPublicKeyBase64 = enc.ephemeralDhPublicKeyBase64,
                sequenceNumber = enc.sequenceNumber,
                prevChainLen = enc.previousChainLength,
                ciphertext = enc.ciphertext,
                iv = enc.iv,
                authTag = tamperedTag
            )
        }

        // 3. Alter sequence number in AAD
        assertThrows(Exception::class.java) {
            bob.decrypt(
                ephemeralDhPublicKeyBase64 = enc.ephemeralDhPublicKeyBase64,
                sequenceNumber = enc.sequenceNumber + 10,
                prevChainLen = enc.previousChainLength,
                ciphertext = enc.ciphertext,
                iv = enc.iv,
                authTag = enc.authTag
            )
        }
    }
}
