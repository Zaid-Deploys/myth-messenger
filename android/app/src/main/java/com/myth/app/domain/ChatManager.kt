package com.myth.app.domain

import com.myth.app.data.local.ChatMessage
import com.myth.app.data.local.VolatileChatStore
import com.myth.app.data.remote.MythApiClient
import com.myth.app.data.remote.MythWebSocketClient
import com.myth.crypto.*
import com.myth.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChatManager(
    private val apiClient: MythApiClient,
    private val wsClient: MythWebSocketClient,
    val chatStore: VolatileChatStore,
    private val callManager: CallManager? = null,
    private val scope: CoroutineScope
) {
    var currentUser: AuthResponse? = null
        private set

    // Device identity keys
    var identityEdKey: Ed25519Key = Ed25519Key.generate()
        private set
    var identityDhKey: X25519Key = X25519Key.generate()
        private set

    // In-memory active E2EE Double Ratchet sessions (ephemeral, not persisted)
    private val sessions = ConcurrentHashMap<String, DoubleRatchetSession>()

    // Local signed prekey and one-time prekeys pool
    private var signedPreKey: X25519Key = X25519Key.generate()
    private val oneTimePreKeys = ConcurrentHashMap<Int, X25519Key>()

    private val _securityAlerts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val securityAlerts: SharedFlow<String> = _securityAlerts.asSharedFlow()

    init {
        // Listen to incoming WebSocket frames
        scope.launch(Dispatchers.Default) {
            wsClient.incomingFrames.collect { frame ->
                handleIncomingFrame(frame)
            }
        }
    }

    fun onUserAuthenticated(auth: AuthResponse) {
        currentUser = auth
        callManager?.currentUser = auth.username
        wsClient.connect(auth.token)
        publishPreKeys(auth.token)
    }

    private fun publishPreKeys(token: String) {
        scope.launch(Dispatchers.IO) {
            val signedPreKeySig = identityEdKey.sign(signedPreKey.publicKeyBytes)
            val opkDtos = mutableListOf<OneTimePreKeyDto>()
            for (i in 1..20) {
                val opk = X25519Key.generate()
                oneTimePreKeys[i] = opk
                opkDtos.add(OneTimePreKeyDto(i, opk.publicKeyBase64))
            }

            val req = RegisterPreKeysRequest(
                identityKeyBase64 = identityEdKey.publicKeyBase64,
                signedPreKeyId = 1,
                signedPreKeyBase64 = signedPreKey.publicKeyBase64,
                signedPreKeySignatureBase64 = CryptoUtils.toBase64(signedPreKeySig),
                oneTimePreKeys = opkDtos
            )
            apiClient.registerPreKeys(token, req)
        }
    }

    suspend fun sendMessage(recipientUsername: String, text: String): Result<String> {
        val user = currentUser ?: return Result.failure(IllegalStateException("Not authenticated"))
        val recipient = recipientUsername.lowercase().trim()

        val session = getOrEstablishSession(recipient)
            ?: return Result.failure(IllegalStateException("Could not establish E2EE session with $recipient"))

        val messageId = UUID.randomUUID().toString()
        val plaintext = text.toByteArray(Charsets.UTF_8)
        val encrypted = session.encrypt(plaintext)

        val submitFrame = MythWebSocketFrame.SubmitMessage(
            messageId = messageId,
            recipientUsername = recipient,
            ephemeralDhPublicKeyBase64 = encrypted.ephemeralDhPublicKeyBase64,
            sequenceNumber = encrypted.sequenceNumber,
            previousChainLength = encrypted.previousChainLength,
            ciphertextBase64 = CryptoUtils.toBase64(encrypted.ciphertext),
            ivBase64 = CryptoUtils.toBase64(encrypted.iv),
            authTagBase64 = CryptoUtils.toBase64(encrypted.authTag),
            timestamp = System.currentTimeMillis()
        )

        val sent = wsClient.sendFrame(submitFrame)
        if (!sent) {
            return Result.failure(IllegalStateException("WebSocket not connected; message not sent"))
        }

        // Add to volatile in-memory store
        chatStore.addMessage(
            ChatMessage(
                id = messageId,
                conversationId = recipient,
                senderUsername = user.username,
                recipientUsername = recipient,
                text = text,
                timestamp = submitFrame.timestamp,
                isOutgoing = true,
                deliveryStatus = DeliveryStatus.DELIVERED
            )
        )

        return Result.success(messageId)
    }

    fun markMessageAsRead(messageId: String, conversationId: String) {
        val user = currentUser ?: return
        val signature = identityEdKey.sign(messageId.toByteArray(Charsets.UTF_8))
        val receiptFrame = MythWebSocketFrame.ReadReceipt(
            messageId = messageId,
            conversationId = conversationId,
            readerUsername = user.username,
            timestamp = System.currentTimeMillis(),
            signatureBase64 = CryptoUtils.toBase64(signature)
        )
        wsClient.sendFrame(receiptFrame)
        // Also trigger self-destruct countdown locally
        chatStore.startSelfDestructCountdown(messageId, 5)
    }

    fun notifyScreenshotCaptured(conversationId: String) {
        val user = currentUser ?: return
        val alertFrame = MythWebSocketFrame.ScreenshotAlert(
            conversationId = conversationId,
            actorUsername = user.username,
            timestamp = System.currentTimeMillis()
        )
        wsClient.sendFrame(alertFrame)
    }

    private suspend fun getOrEstablishSession(targetUsername: String): DoubleRatchetSession? {
        val existing = sessions[targetUsername]
        if (existing != null) return existing

        val user = currentUser ?: return null
        val bundleResult = apiClient.getPreKeyBundle(user.token, targetUsername)
        val bundle = bundleResult.getOrNull() ?: return null

        val initiatorResult = X3DH.initiate(identityDhKey, bundle)
        val remoteIdentityDhPub = X25519Key.fromPublicKeyBase64(bundle.signedPreKeyBase64)

        val newSession = DoubleRatchetSession.createAsInitiator(
            conversationId = targetUsername,
            localUsername = user.username,
            remoteUsername = targetUsername,
            masterSharedSecret = initiatorResult.sharedMasterKey,
            remoteIdentityDhPublicKey = remoteIdentityDhPub
        )
        sessions[targetUsername] = newSession
        return newSession
    }

    private fun handleIncomingFrame(frame: MythWebSocketFrame) {
        when (frame) {
            is MythWebSocketFrame.RelayedMessage -> {
                val sender = frame.senderUsername.lowercase().trim()
                var session = sessions[sender]
                if (session == null) {
                    // Initialize receiving session
                    val dummySecret = CryptoUtils.randomBytes(32)
                    session = DoubleRatchetSession.createAsRecipient(
                        conversationId = sender,
                        localUsername = currentUser?.username ?: "",
                        remoteUsername = sender,
                        masterSharedSecret = dummySecret,
                        localKey = signedPreKey
                    )
                    sessions[sender] = session
                }

                try {
                    val decryptedBytes = session.decrypt(
                        ephemeralDhPublicKeyBase64 = frame.ephemeralDhPublicKeyBase64,
                        sequenceNumber = frame.sequenceNumber,
                        prevChainLen = frame.previousChainLength,
                        ciphertext = CryptoUtils.fromBase64(frame.ciphertextBase64),
                        iv = CryptoUtils.fromBase64(frame.ivBase64),
                        authTag = CryptoUtils.fromBase64(frame.authTagBase64)
                    )
                    val text = String(decryptedBytes, Charsets.UTF_8)
                    chatStore.addMessage(
                        ChatMessage(
                            id = frame.messageId,
                            conversationId = sender,
                            senderUsername = sender,
                            recipientUsername = currentUser?.username ?: "",
                            text = text,
                            timestamp = frame.timestamp,
                            isOutgoing = false,
                            deliveryStatus = DeliveryStatus.DELIVERED
                        )
                    )
                } catch (e: Exception) {
                    scope.launch {
                        _securityAlerts.emit("Decryption failed for message from $sender: message may have been tampered with.")
                    }
                }
            }

            is MythWebSocketFrame.ReadReceipt -> {
                chatStore.markReadByPeer(frame.messageId)
                chatStore.startSelfDestructCountdown(frame.messageId, 5)
            }

            is MythWebSocketFrame.SelfDestructNotice -> {
                chatStore.startSelfDestructCountdown(frame.messageId, frame.countdownSeconds)
            }

            is MythWebSocketFrame.ScreenshotAlert -> {
                scope.launch {
                    _securityAlerts.emit("SECURITY NOTICE: ${frame.actorUsername} attempted a screenshot!")
                }
            }

            is MythWebSocketFrame.DeliveryStatusNotice -> {
                chatStore.updateDeliveryStatus(frame.messageId, frame.status)
            }

            else -> {}
        }
    }

    fun logout() {
        currentUser = null
        callManager?.currentUser = null
        wsClient.disconnect()
        sessions.clear()
        chatStore.clearAllOnLogout()
    }
}
