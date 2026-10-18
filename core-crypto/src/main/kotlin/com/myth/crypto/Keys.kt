package com.myth.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

class X25519Key(
    val privateKey: X25519PrivateKeyParameters?,
    val publicKey: X25519PublicKeyParameters
) {
    val publicKeyBytes: ByteArray get() = publicKey.encoded
    val publicKeyBase64: String get() = CryptoUtils.toBase64(publicKeyBytes)

    fun calculateAgreement(otherPublicKey: X25519PublicKeyParameters): ByteArray {
        requireNotNull(privateKey) { "Cannot calculate agreement without private key" }
        val agreement = X25519Agreement()
        agreement.init(privateKey)
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(otherPublicKey, secret, 0)
        return secret
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): X25519Key {
            val generator = X25519KeyPairGenerator()
            generator.init(X25519KeyGenerationParameters(random))
            val pair = generator.generateKeyPair()
            return X25519Key(
                pair.private as X25519PrivateKeyParameters,
                pair.public as X25519PublicKeyParameters
            )
        }

        fun fromPublicKeyBase64(b64: String): X25519PublicKeyParameters {
            val bytes = CryptoUtils.fromBase64(b64)
            return X25519PublicKeyParameters(bytes, 0)
        }

        fun fromPrivateKeyBytes(privBytes: ByteArray, pubBytes: ByteArray): X25519Key {
            return X25519Key(
                X25519PrivateKeyParameters(privBytes, 0),
                X25519PublicKeyParameters(pubBytes, 0)
            )
        }
    }
}

class Ed25519Key(
    val privateKey: Ed25519PrivateKeyParameters?,
    val publicKey: Ed25519PublicKeyParameters
) {
    val publicKeyBytes: ByteArray get() = publicKey.encoded
    val publicKeyBase64: String get() = CryptoUtils.toBase64(publicKeyBytes)

    fun sign(data: ByteArray): ByteArray {
        requireNotNull(privateKey) { "Cannot sign without private key" }
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun verify(data: ByteArray, signature: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, publicKey)
        signer.update(data, 0, data.size)
        return signer.verifySignature(signature)
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): Ed25519Key {
            val generator = Ed25519KeyPairGenerator()
            generator.init(Ed25519KeyGenerationParameters(random))
            val pair = generator.generateKeyPair()
            return Ed25519Key(
                pair.private as Ed25519PrivateKeyParameters,
                pair.public as Ed25519PublicKeyParameters
            )
        }

        fun fromPublicKeyBase64(b64: String): Ed25519PublicKeyParameters {
            val bytes = CryptoUtils.fromBase64(b64)
            return Ed25519PublicKeyParameters(bytes, 0)
        }

        fun fromPrivateKeyBytes(privBytes: ByteArray, pubBytes: ByteArray): Ed25519Key {
            return Ed25519Key(
                Ed25519PrivateKeyParameters(privBytes, 0),
                Ed25519PublicKeyParameters(pubBytes, 0)
            )
        }
    }
}

object Kdf {
    fun deriveKey(ikm: ByteArray, salt: ByteArray? = null, info: ByteArray? = null, outputLength: Int = 32): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, info))
        val output = ByteArray(outputLength)
        hkdf.generateBytes(output, 0, outputLength)
        return output
    }

    fun deriveSecrets(ikm: ByteArray, salt: ByteArray?, info: ByteArray?, lengthA: Int = 32, lengthB: Int = 32): Pair<ByteArray, ByteArray> {
        val totalLength = lengthA + lengthB
        val derived = deriveKey(ikm, salt, info, totalLength)
        val a = derived.copyOfRange(0, lengthA)
        val b = derived.copyOfRange(lengthA, totalLength)
        CryptoUtils.zeroize(derived)
        return Pair(a, b)
    }
}
