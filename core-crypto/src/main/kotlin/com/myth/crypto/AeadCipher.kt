package com.myth.crypto

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

data class EncryptedPayload(
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val authTag: ByteArray
) {
    val ciphertextBase64: String get() = CryptoUtils.toBase64(ciphertext)
    val ivBase64: String get() = CryptoUtils.toBase64(iv)
    val authTagBase64: String get() = CryptoUtils.toBase64(authTag)
}

object AeadCipher {
    private const val NONCE_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128
    private const val TAG_LENGTH_BYTES = 16

    fun encrypt(
        key: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray? = null,
        iv: ByteArray = CryptoUtils.randomBytes(NONCE_LENGTH_BYTES)
    ): EncryptedPayload {
        require(key.size == 32) { "Key length must be 32 bytes (256 bits)" }
        require(iv.size == NONCE_LENGTH_BYTES) { "IV length must be $NONCE_LENGTH_BYTES bytes" }

        val cipher = ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(key), TAG_LENGTH_BITS, iv, associatedData)
        cipher.init(true, params)

        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        cipher.doFinal(output, len)

        // output contains ciphertext + 16 bytes tag
        val ciphertext = output.copyOfRange(0, output.size - TAG_LENGTH_BYTES)
        val tag = output.copyOfRange(output.size - TAG_LENGTH_BYTES, output.size)

        return EncryptedPayload(
            ciphertext = ciphertext,
            iv = iv,
            authTag = tag
        )
    }

    fun decrypt(
        key: ByteArray,
        ciphertext: ByteArray,
        iv: ByteArray,
        authTag: ByteArray,
        associatedData: ByteArray? = null
    ): ByteArray {
        require(key.size == 32) { "Key length must be 32 bytes" }
        require(iv.size == NONCE_LENGTH_BYTES) { "IV length must be $NONCE_LENGTH_BYTES bytes" }
        require(authTag.size == TAG_LENGTH_BYTES) { "Auth tag must be $TAG_LENGTH_BYTES bytes" }

        val cipher = ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(key), TAG_LENGTH_BITS, iv, associatedData)
        cipher.init(false, params)

        val combined = ByteArray(ciphertext.size + authTag.size)
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
        System.arraycopy(authTag, 0, combined, ciphertext.size, authTag.size)

        val output = ByteArray(cipher.getOutputSize(combined.size))
        val len = cipher.processBytes(combined, 0, combined.size, output, 0)
        val finalLen = cipher.doFinal(output, len)

        return output.copyOf(len + finalLen)
    }
}
