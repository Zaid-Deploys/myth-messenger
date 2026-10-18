package com.myth.crypto

import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class DoubleRatchetSession(
    val conversationId: String,
    val localUsername: String,
    val remoteUsername: String,
    private var rootKey: ByteArray,
    private var dhSendingKey: X25519Key,
    private var dhReceivingPublicKey: X25519PublicKeyParameters?,
    private var sendingChainKey: ByteArray? = null,
    private var receivingChainKey: ByteArray? = null
) {
    private var sendSequenceNumber: Long = 0
    private var receiveSequenceNumber: Long = 0
    private var previousChainLength: Long = 0

    // Bounded cache for skipped message keys (keyed by "DHPubBase64:SequenceNumber")
    private val skippedMessageKeys = mutableMapOf<String, ByteArray>()
    private val MAX_SKIPPED_KEYS = 50

    val currentDhSendingPublicKeyBase64: String
        get() = dhSendingKey.publicKeyBase64

    @Synchronized
    fun encrypt(plaintext: ByteArray): EncryptedMessagePayload {
        // Ensure sending chain exists; if not, step root key
        if (sendingChainKey == null) {
            val remotePub = checkNotNull(dhReceivingPublicKey) { "Cannot send without remote DH public key" }
            val dhSecret = dhSendingKey.calculateAgreement(remotePub)
            val (nextRoot, newSendingChain) = Kdf.deriveSecrets(
                ikm = dhSecret,
                salt = rootKey,
                info = "Myth-DHRatchet".toByteArray(Charsets.UTF_8)
            )
            CryptoUtils.zeroize(dhSecret)
            rootKey = nextRoot
            sendingChainKey = newSendingChain
        }

        val currentSendingChain = sendingChainKey!!
        // Advance sending chain KDF: (nextSendingChain, messageKey)
        val (nextChain, messageKey) = Kdf.deriveSecrets(
            ikm = currentSendingChain,
            salt = null,
            info = "Myth-MessageKey".toByteArray(Charsets.UTF_8)
        )
        sendingChainKey = nextChain

        val currentSeq = sendSequenceNumber++

        // Build authenticated Associated Authenticated Data (AAD)
        val aad = buildAad(conversationId, localUsername, currentSeq, previousChainLength)

        // Encrypt message using ChaCha20-Poly1305
        val encrypted = AeadCipher.encrypt(
            key = messageKey,
            plaintext = plaintext,
            associatedData = aad
        )

        // Forward secrecy: Zeroize message key immediately
        CryptoUtils.zeroize(messageKey)

        return EncryptedMessagePayload(
            ephemeralDhPublicKeyBase64 = dhSendingKey.publicKeyBase64,
            sequenceNumber = currentSeq,
            previousChainLength = previousChainLength,
            ciphertext = encrypted.ciphertext,
            iv = encrypted.iv,
            authTag = encrypted.authTag
        )
    }

    @Synchronized
    fun decrypt(
        ephemeralDhPublicKeyBase64: String,
        sequenceNumber: Long,
        prevChainLen: Long,
        ciphertext: ByteArray,
        iv: ByteArray,
        authTag: ByteArray
    ): ByteArray {
        val remoteEphemeralPub = X25519Key.fromPublicKeyBase64(ephemeralDhPublicKeyBase64)
        val aad = buildAad(conversationId, remoteUsername, sequenceNumber, prevChainLen)

        // Check if message key was previously skipped and stored
        val skippedKeyId = "$ephemeralDhPublicKeyBase64:$sequenceNumber"
        val existingSkippedKey = skippedMessageKeys.remove(skippedKeyId)
        if (existingSkippedKey != null) {
            val decrypted = AeadCipher.decrypt(existingSkippedKey, ciphertext, iv, authTag, aad)
            CryptoUtils.zeroize(existingSkippedKey)
            return decrypted
        }

        // If a new remote DH key is received, perform DH Ratchet step
        if (dhReceivingPublicKey == null || !CryptoUtils.constantTimeEquals(dhReceivingPublicKey!!.encoded, remoteEphemeralPub.encoded)) {
            // Skip any remaining keys in current receiving chain if present
            if (receivingChainKey != null) {
                skipMessageKeys(prevChainLen)
            }
            dhRatchetStep(remoteEphemeralPub)
        }

        // Skip any skipped messages in current receiving chain up to sequenceNumber
        skipMessageKeys(sequenceNumber)

        val currentRecvChain = checkNotNull(receivingChainKey) { "Receiving chain must exist" }
        val (nextChain, messageKey) = Kdf.deriveSecrets(
            ikm = currentRecvChain,
            salt = null,
            info = "Myth-MessageKey".toByteArray(Charsets.UTF_8)
        )
        receivingChainKey = nextChain
        receiveSequenceNumber = sequenceNumber + 1

        val decrypted = AeadCipher.decrypt(
            key = messageKey,
            ciphertext = ciphertext,
            iv = iv,
            authTag = authTag,
            associatedData = aad
        )

        // Forward secrecy: Zeroize message key immediately
        CryptoUtils.zeroize(messageKey)
        return decrypted
    }

    private fun dhRatchetStep(newRemoteDhPub: X25519PublicKeyParameters) {
        previousChainLength = sendSequenceNumber
        sendSequenceNumber = 0
        receiveSequenceNumber = 0
        dhReceivingPublicKey = newRemoteDhPub

        // DH ratchet receiving step
        val dhReceiveSecret = dhSendingKey.calculateAgreement(newRemoteDhPub)
        val (rootAfterRecv, newRecvChain) = Kdf.deriveSecrets(
            ikm = dhReceiveSecret,
            salt = rootKey,
            info = "Myth-DHRatchet".toByteArray(Charsets.UTF_8)
        )
        CryptoUtils.zeroize(dhReceiveSecret)
        rootKey = rootAfterRecv
        receivingChainKey = newRecvChain

        // Generate new local DH key pair and perform DH ratchet sending step
        dhSendingKey = X25519Key.generate()
        val dhSendSecret = dhSendingKey.calculateAgreement(newRemoteDhPub)
        val (rootAfterSend, newSendChain) = Kdf.deriveSecrets(
            ikm = dhSendSecret,
            salt = rootKey,
            info = "Myth-DHRatchet".toByteArray(Charsets.UTF_8)
        )
        CryptoUtils.zeroize(dhSendSecret)
        rootKey = rootAfterSend
        sendingChainKey = newSendChain
    }

    private fun skipMessageKeys(untilSequence: Long) {
        if (receiveSequenceNumber + 500 < untilSequence) {
            throw IllegalArgumentException("Too many skipped messages: sequence drift exceeds bounds")
        }
        val currentPub = dhReceivingPublicKey?.encoded?.let { CryptoUtils.toBase64(it) } ?: return
        while (receiveSequenceNumber < untilSequence) {
            val (nextChain, skippedKey) = Kdf.deriveSecrets(
                ikm = receivingChainKey!!,
                salt = null,
                info = "Myth-MessageKey".toByteArray(Charsets.UTF_8)
            )
            receivingChainKey = nextChain
            val keyId = "$currentPub:$receiveSequenceNumber"
            if (skippedMessageKeys.size >= MAX_SKIPPED_KEYS) {
                // Evict oldest skipped key to bound memory
                val firstKey = skippedMessageKeys.keys.first()
                val removed = skippedMessageKeys.remove(firstKey)
                if (removed != null) CryptoUtils.zeroize(removed)
            }
            skippedMessageKeys[keyId] = skippedKey
            receiveSequenceNumber++
        }
    }

    private fun buildAad(convId: String, sender: String, seq: Long, prevLen: Long): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write("Myth-AAD-v1".toByteArray(Charsets.UTF_8))
        baos.write(convId.toByteArray(Charsets.UTF_8))
        baos.write(sender.toByteArray(Charsets.UTF_8))
        val buf = ByteBuffer.allocate(16)
        buf.putLong(seq)
        buf.putLong(prevLen)
        baos.write(buf.array())
        return baos.toByteArray()
    }

    companion object {
        fun createAsInitiator(
            conversationId: String,
            localUsername: String,
            remoteUsername: String,
            masterSharedSecret: ByteArray,
            remoteIdentityDhPublicKey: X25519PublicKeyParameters
        ): DoubleRatchetSession {
            val localSendingKey = X25519Key.generate()
            return DoubleRatchetSession(
                conversationId = conversationId,
                localUsername = localUsername,
                remoteUsername = remoteUsername,
                rootKey = masterSharedSecret,
                dhSendingKey = localSendingKey,
                dhReceivingPublicKey = remoteIdentityDhPublicKey
            )
        }

        fun createAsRecipient(
            conversationId: String,
            localUsername: String,
            remoteUsername: String,
            masterSharedSecret: ByteArray,
            localKey: X25519Key
        ): DoubleRatchetSession {
            return DoubleRatchetSession(
                conversationId = conversationId,
                localUsername = localUsername,
                remoteUsername = remoteUsername,
                rootKey = masterSharedSecret,
                dhSendingKey = localKey,
                dhReceivingPublicKey = null
            )
        }
    }
}

data class EncryptedMessagePayload(
    val ephemeralDhPublicKeyBase64: String,
    val sequenceNumber: Long,
    val previousChainLength: Long,
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val authTag: ByteArray
)
