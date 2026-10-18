package com.myth.model

import kotlinx.serialization.Serializable

@Serializable
data class OneTimePreKeyDto(
    val keyId: Int,
    val publicKeyBase64: String
)

@Serializable
data class RegisterPreKeysRequest(
    val identityKeyBase64: String,
    val signedPreKeyId: Int,
    val signedPreKeyBase64: String,
    val signedPreKeySignatureBase64: String,
    val oneTimePreKeys: List<OneTimePreKeyDto>
)

@Serializable
data class PreKeyBundleDto(
    val userId: String,
    val username: String,
    val identityKeyBase64: String,
    val signedPreKeyId: Int,
    val signedPreKeyBase64: String,
    val signedPreKeySignatureBase64: String,
    val oneTimePreKey: OneTimePreKeyDto? = null
)
