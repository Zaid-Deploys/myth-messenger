package com.myth.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DoubleRatchetTest {

    @Test
    fun testTwoPartyMessagingAndRatcheting() {
        val conversationId = "conv-12345"
        val masterSharedSecret = CryptoUtils.randomBytes(32)

        val bobDhKey = X25519Key.generate()

        // Alice initializes as initiator
        val aliceSession = DoubleRatchetSession.createAsInitiator(
            conversationId = conversationId,
            localUsername = "alice",
            remoteUsername = "bob",
            masterSharedSecret = masterSharedSecret.copyOf(),
            remoteIdentityDhPublicKey = bobDhKey.publicKey
        )

        // Bob initializes as recipient
        val bobSession = DoubleRatchetSession.createAsRecipient(
            conversationId = conversationId,
            localUsername = "bob",
            remoteUsername = "alice",
            masterSharedSecret = masterSharedSecret.copyOf(),
            localKey = bobDhKey
        )

        // 1. Alice sends message 1 to Bob
        val plain1 = "Hello Bob, this is a secret end-to-end encrypted message!".toByteArray(Charsets.UTF_8)
        val enc1 = aliceSession.encrypt(plain1)

        val decrypted1 = bobSession.decrypt(
            ephemeralDhPublicKeyBase64 = enc1.ephemeralDhPublicKeyBase64,
            sequenceNumber = enc1.sequenceNumber,
            prevChainLen = enc1.previousChainLength,
            ciphertext = enc1.ciphertext,
            iv = enc1.iv,
            authTag = enc1.authTag
        )
        assertEquals(String(plain1), String(decrypted1))

        // 2. Alice sends message 2 to Bob (in same sending chain)
        val plain2 = "Second message in the chain".toByteArray(Charsets.UTF_8)
        val enc2 = aliceSession.encrypt(plain2)
        assertEquals(0L, enc1.sequenceNumber)
        assertEquals(1L, enc2.sequenceNumber)

        val decrypted2 = bobSession.decrypt(
            ephemeralDhPublicKeyBase64 = enc2.ephemeralDhPublicKeyBase64,
            sequenceNumber = enc2.sequenceNumber,
            prevChainLen = enc2.previousChainLength,
            ciphertext = enc2.ciphertext,
            iv = enc2.iv,
            authTag = enc2.authTag
        )
        assertEquals(String(plain2), String(decrypted2))

        // 3. Bob replies to Alice (triggers DH Ratchet step)
        val bobReply = "Hi Alice! Received your messages and sending this reply.".toByteArray(Charsets.UTF_8)
        val encReply = bobSession.encrypt(bobReply)
        assertNotEquals(enc1.ephemeralDhPublicKeyBase64, encReply.ephemeralDhPublicKeyBase64)

        val decryptedReply = aliceSession.decrypt(
            ephemeralDhPublicKeyBase64 = encReply.ephemeralDhPublicKeyBase64,
            sequenceNumber = encReply.sequenceNumber,
            prevChainLen = encReply.previousChainLength,
            ciphertext = encReply.ciphertext,
            iv = encReply.iv,
            authTag = encReply.authTag
        )
        assertEquals(String(bobReply), String(decryptedReply))

        // 4. Alice replies back (another DH Ratchet step)
        val aliceReply2 = "Great, post-compromise security ratchet confirmed!".toByteArray(Charsets.UTF_8)
        val encAlice2 = aliceSession.encrypt(aliceReply2)

        val decAlice2 = bobSession.decrypt(
            ephemeralDhPublicKeyBase64 = encAlice2.ephemeralDhPublicKeyBase64,
            sequenceNumber = encAlice2.sequenceNumber,
            prevChainLen = encAlice2.previousChainLength,
            ciphertext = encAlice2.ciphertext,
            iv = encAlice2.iv,
            authTag = encAlice2.authTag
        )
        assertEquals(String(aliceReply2), String(decAlice2))
    }
}
