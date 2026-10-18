package com.myth.model

import kotlinx.serialization.Serializable

@Serializable
enum class ContactStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    BLOCKED
}

@Serializable
data class ContactDto(
    val id: String,
    val username: String,
    val status: ContactStatus,
    val createdAt: Long,
    val identityKeyBase64: String? = null
)

@Serializable
data class ContactRequestDto(
    val requestId: String,
    val requesterUsername: String,
    val addresseeUsername: String,
    val status: ContactStatus,
    val createdAt: Long
)

@Serializable
data class CreateContactRequest(
    val targetUsername: String
)

@Serializable
data class RespondContactRequest(
    val requestId: String,
    val accept: Boolean
)

@Serializable
data class BlockContactRequest(
    val targetUsername: String
)

@Serializable
data class UnblockContactRequest(
    val targetUsername: String
)

@Serializable
data class RemoveContactRequest(
    val targetUsername: String
)

@Serializable
data class ContactListResponse(
    val contacts: List<ContactDto>,
    val incomingRequests: List<ContactRequestDto>,
    val outgoingRequests: List<ContactRequestDto>,
    val blockedUsers: List<String>
)
