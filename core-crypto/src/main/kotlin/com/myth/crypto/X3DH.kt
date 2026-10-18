package com.myth.crypto

import com.myth.model.OneTimePreKeyDto
import com.myth.model.PreKeyBundleDto
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.ByteArrayOutputStream

data class X3DHInitiatorResult(
    val sharedMasterKey: ByteArray,
    val ephemeralKey: X25519Key,
    val oneTimePreKeyIdUsed: Int?
)

object X3DH {
    private val INFO_X3DH = "Myth-X3DH-v1".toByteArray(Charsets.UTF_8)

    /**
     * Alice initiates X3DH key agreement using Bob's published PreKeyBundle.
     */
    fun initiate(
        aliceIdentityDhKey: X25519Key,
        bobBundle: PreKeyBundleDto
    ): X3DHInitiatorResult {
        // 1. Verify Bob's Signed PreKey signature using Bob's Ed25519 Identity Key
        val bobIdentityEd25519Pub = Ed25519Key.fromPublicKeyBase64(bobBundle.identityKeyBase64)
        val bobSignedPreKeyPub = X25519Key.fromPublicKeyBase64(bobBundle.signedPreKeyBase64)
        val signature = CryptoUtils.fromBase64(bobBundle.signedPreKeySignatureBase64)

        val edKey = Ed25519Key(null, bobIdentityEd25519Pub)
        val validSig = edKey.verify(bobSignedPreKeyPub.encoded, signature)
        require(validSig) { "Invalid signature on recipient signed prekey" }

        // 2. Generate Alice ephemeral DH key
        val aliceEphemeral = X25519Key.generate()

        // 3. Convert Bob's identity key to DH public key or use dedicated DH identity key
        // In this implementation, Bob's identity key bundle provides X25519 public key bytes
        val bobIdentityDhPub = X25519PublicKeyParameters(bobIdentityEd25519Pub.encoded, 0)

        // DH1 = DH(IK_A, SPK_B)
        val dh1 = aliceIdentityDhKey.calculateAgreement(bobSignedPreKeyPub)
        // DH2 = DH(EK_A, IK_B)
        val dh2 = aliceEphemeral.calculateAgreement(bobIdentityDhPub)
        // DH3 = DH(EK_A, SPK_B)
        val dh3 = aliceEphemeral.calculateAgreement(bobSignedPreKeyPub)

        val baos = ByteArrayOutputStream()
        baos.write(dh1)
        baos.write(dh2)
        baos.write(dh3)

        // DH4 = DH(EK_A, OPK_B) if present
        val opkIdUsed = bobBundle.oneTimePreKey?.let { opkDto ->
            val bobOpkPub = X25519Key.fromPublicKeyBase64(opkDto.publicKeyBase64)
            val dh4 = aliceEphemeral.calculateAgreement(bobOpkPub)
            baos.write(dh4)
            opkDto.keyId
        }

        val ikm = baos.toByteArray()
        val sk = Kdf.deriveKey(ikm = ikm, salt = null, info = INFO_X3DH, outputLength = 32)
        CryptoUtils.zeroize(ikm)

        return X3DHInitiatorResult(
            sharedMasterKey = sk,
            ephemeralKey = aliceEphemeral,
            oneTimePreKeyIdUsed = opkIdUsed
        )
    }

    /**
     * Bob receives Alice's initial handshake and computes the identical shared master key.
     */
    fun receive(
        bobIdentityDhKey: X25519Key,
        bobSignedPreKey: X25519Key,
        bobOneTimePreKey: X25519Key?,
        aliceIdentityDhPub: X25519PublicKeyParameters,
        aliceEphemeralPub: X25519PublicKeyParameters
    ): ByteArray {
        // DH1 = DH(SPK_B, IK_A)
        val dh1 = bobSignedPreKey.calculateAgreement(aliceIdentityDhPub)
        // DH2 = DH(IK_B, EK_A)
        val dh2 = bobIdentityDhKey.calculateAgreement(aliceEphemeralPub)
        // DH3 = DH(SPK_B, EK_A)
        val dh3 = bobSignedPreKey.calculateAgreement(aliceEphemeralPub)

        val baos = ByteArrayOutputStream()
        baos.write(dh1)
        baos.write(dh2)
        baos.write(dh3)

        if (bobOneTimePreKey != null) {
            val dh4 = bobOneTimePreKey.calculateAgreement(aliceEphemeralPub)
            baos.write(dh4)
        }

        val ikm = baos.toByteArray()
        val sk = Kdf.deriveKey(ikm = ikm, salt = null, info = INFO_X3DH, outputLength = 32)
        CryptoUtils.zeroize(ikm)
        return sk
    }
}
