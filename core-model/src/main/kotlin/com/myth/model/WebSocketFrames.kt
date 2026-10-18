package com.myth.model

import kotlinx.serialization.Serializable

@Serializable
enum class DeliveryStatus {
    DELIVERED,
    UNDELIVERED,
    EXPIRED,
    FAILED
}

@Serializable
sealed class MythWebSocketFrame {

    @Serializable
    data class Auth(
        val token: String
    ) : MythWebSocketFrame()

    @Serializable
    data class AuthSuccess(
        val userId: String,
        val username: String
    ) : MythWebSocketFrame()

    @Serializable
    data class SubmitMessage(
        val messageId: String,
        val recipientUsername: String,
        val ephemeralDhPublicKeyBase64: String,
        val sequenceNumber: Long,
        val previousChainLength: Long,
        val ciphertextBase64: String,
        val ivBase64: String,
        val authTagBase64: String,
        val timestamp: Long
    ) : MythWebSocketFrame()

    @Serializable
    data class RelayedMessage(
        val messageId: String,
        val senderUsername: String,
        val ephemeralDhPublicKeyBase64: String,
        val sequenceNumber: Long,
        val previousChainLength: Long,
        val ciphertextBase64: String,
        val ivBase64: String,
        val authTagBase64: String,
        val timestamp: Long
    ) : MythWebSocketFrame()

    @Serializable
    data class ReadReceipt(
        val messageId: String,
        val conversationId: String,
        val readerUsername: String,
        val timestamp: Long,
        val signatureBase64: String
    ) : MythWebSocketFrame()

    @Serializable
    data class SelfDestructNotice(
        val messageId: String,
        val conversationId: String,
        val reason: String = "Read expiration countdown triggered",
        val countdownSeconds: Int = 5,
        val timestamp: Long
    ) : MythWebSocketFrame()

    @Serializable
    data class ScreenshotAlert(
        val conversationId: String,
        val actorUsername: String,
        val timestamp: Long
    ) : MythWebSocketFrame()

    @Serializable
    data class DeliveryStatusNotice(
        val messageId: String,
        val status: DeliveryStatus,
        val reason: String? = null,
        val timestamp: Long
    ) : MythWebSocketFrame()

    @Serializable
    data class Heartbeat(
        val timestamp: Long
    ) : MythWebSocketFrame()

    @Serializable
    data class Error(
        val code: String,
        val message: String
    ) : MythWebSocketFrame()

    // --- WebRTC Voice Call Signaling Frames ---

    @Serializable
    data class CallOffer(
        val callId: String,
        val recipientUsername: String,
        val sdpOffer: String,
        val timestamp: Long
    ) : MythWebSocketFrame()

    @Serializable
    data class IncomingCall(
        val callId: String,
        val callerUsername: String,
        val sdpOffer: String,
        val timestamp: Long
    ) : MythWebSocketFrame()

    @Serializable
    data class CallAnswer(
        val callId: String,
        val recipientUsername: String,
        val sdpAnswer: String,
        val timestamp: Long
    ) : MythWebSocketFrame()

    @Serializable
    data class IceCandidate(
        val callId: String,
        val recipientUsername: String,
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val sdpCandidate: String
    ) : MythWebSocketFrame()

    @Serializable
    data class CallReject(
        val callId: String,
        val recipientUsername: String,
        val reason: String = "DECLINED"
    ) : MythWebSocketFrame()

    @Serializable
    data class CallEnd(
        val callId: String,
        val recipientUsername: String,
        val reason: String = "NORMAL_HANGUP"
    ) : MythWebSocketFrame()
}
