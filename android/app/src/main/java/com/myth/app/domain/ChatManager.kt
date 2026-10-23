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
    private val context: android.content.Context? = null,
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

    private fun loadOrInitKeys(ctx: android.content.Context, username: String) {
        val prefs = ctx.getSharedPreferences("myth_keys_$username", android.content.Context.MODE_PRIVATE)
        val edPriv = prefs.getString("ed_priv", null)
        val edPub = prefs.getString("ed_pub", null)
        val dhPriv = prefs.getString("dh_priv", null)
        val dhPub = prefs.getString("dh_pub", null)
        val spkPriv = prefs.getString("spk_priv", null)
        val spkPub = prefs.getString("spk_pub", null)

        if (edPriv != null && edPub != null && dhPriv != null && dhPub != null && spkPriv != null && spkPub != null) {
            try {
                identityEdKey = Ed25519Key.fromPrivateKeyBytes(CryptoUtils.fromBase64(edPriv), CryptoUtils.fromBase64(edPub))
                identityDhKey = X25519Key.fromPrivateKeyBytes(CryptoUtils.fromBase64(dhPriv), CryptoUtils.fromBase64(dhPub))
                signedPreKey = X25519Key.fromPrivateKeyBytes(CryptoUtils.fromBase64(spkPriv), CryptoUtils.fromBase64(spkPub))
                return
            } catch (e: Exception) {
                android.util.Log.e("ChatManager", "Failed to restore keys from prefs, regenerating", e)
            }
        }

        identityEdKey = Ed25519Key.generate()
        identityDhKey = X25519Key.generate()
        signedPreKey = X25519Key.generate()

        prefs.edit()
            .putString("ed_priv", CryptoUtils.toBase64(identityEdKey.privateKey!!.encoded))
            .putString("ed_pub", CryptoUtils.toBase64(identityEdKey.publicKeyBytes))
            .putString("dh_priv", CryptoUtils.toBase64(identityDhKey.privateKey!!.encoded))
            .putString("dh_pub", CryptoUtils.toBase64(identityDhKey.publicKeyBytes))
            .putString("spk_priv", CryptoUtils.toBase64(signedPreKey.privateKey!!.encoded))
            .putString("spk_pub", CryptoUtils.toBase64(signedPreKey.publicKeyBytes))
            .apply()
    }

    fun onUserAuthenticated(auth: AuthResponse) {
        currentUser = auth
        context?.let { ctx ->
            loadOrInitKeys(ctx, auth.username)
            val prefs = ctx.getSharedPreferences("myth_auth_session", android.content.Context.MODE_PRIVATE)
            prefs.edit()
                .putString("user_id", auth.userId)
                .putString("username", auth.username)
                .putString("token", auth.token)
                .putLong("created_at", auth.createdAt)
                .apply()
        }
        callManager?.currentUser = auth.username
        wsClient.connect(auth.token)
        publishPreKeys(auth.token)
    }

    fun restorePersistedSession(): Boolean {
        val ctx = context ?: return false
        val prefs = ctx.getSharedPreferences("myth_auth_session", android.content.Context.MODE_PRIVATE)
        val token = prefs.getString("token", null) ?: return false
        val username = prefs.getString("username", null) ?: return false
        val userId = prefs.getString("user_id", null) ?: return false
        val createdAt = prefs.getLong("created_at", System.currentTimeMillis())

        val auth = AuthResponse(token = token, userId = userId, username = username, createdAt = createdAt)
        currentUser = auth
        loadOrInitKeys(ctx, username)
        callManager?.currentUser = username
        wsClient.connect(token)
        publishPreKeys(token)
        return true
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

        val bobSignedPreKeyPub = X25519Key.fromPublicKeyBase64(bundle.signedPreKeyBase64)
        val sharedDh = signedPreKey.calculateAgreement(bobSignedPreKeyPub)
        val masterSecret = Kdf.deriveKey(
            ikm = sharedDh,
            salt = null,
            info = "Myth-SharedSecret-v1".toByteArray(Charsets.UTF_8),
            outputLength = 32
        )

        val canonicalConvId = listOf(user.username.lowercase().trim(), targetUsername.lowercase().trim()).sorted().joinToString(":")
        val newSession = DoubleRatchetSession.createAsInitiator(
            conversationId = canonicalConvId,
            localUsername = user.username,
            remoteUsername = targetUsername,
            masterSharedSecret = masterSecret,
            remoteIdentityDhPublicKey = bobSignedPreKeyPub
        )
        sessions[targetUsername] = newSession
        return newSession
    }

    private fun handleIncomingFrame(frame: MythWebSocketFrame) {
        when (frame) {
            is MythWebSocketFrame.RelayedMessage -> {
                val sender = frame.senderUsername.lowercase().trim()
                scope.launch(Dispatchers.IO) {
                    var session = sessions[sender]
                    if (session == null) {
                        val user = currentUser
                        if (user != null) {
                            val bundleResult = apiClient.getPreKeyBundle(user.token, sender)
                            val bundle = bundleResult.getOrNull()
                            if (bundle != null) {
                                val senderSignedPreKeyPub = X25519Key.fromPublicKeyBase64(bundle.signedPreKeyBase64)
                                val sharedDh = signedPreKey.calculateAgreement(senderSignedPreKeyPub)
                                val masterSecret = Kdf.deriveKey(
                                    ikm = sharedDh,
                                    salt = null,
                                    info = "Myth-SharedSecret-v1".toByteArray(Charsets.UTF_8),
                                    outputLength = 32
                                )
                                val canonicalConvId = listOf(user.username.lowercase().trim(), sender.lowercase().trim()).sorted().joinToString(":")
                                session = DoubleRatchetSession.createAsRecipient(
                                    conversationId = canonicalConvId,
                                    localUsername = user.username,
                                    remoteUsername = sender,
                                    masterSharedSecret = masterSecret,
                                    localKey = signedPreKey
                                )
                                sessions[sender] = session
                            }
                        }
                    }

                    if (session == null) {
                        android.util.Log.e("ChatManager", "Could not establish recipient session for $sender")
                        _securityAlerts.emit("Could not establish secure session with $sender")
                        return@launch
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
                        markMessageAsRead(frame.messageId, sender)
                    } catch (e: Exception) {
                        android.util.Log.e("ChatManager", "Decryption failed for message from $sender", e)
                        sessions.remove(sender)
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
        context?.getSharedPreferences("myth_auth_session", android.content.Context.MODE_PRIVATE)
            ?.edit()?.clear()?.apply()
        currentUser = null
        callManager?.currentUser = null
        wsClient.disconnect()
        sessions.clear()
        chatStore.clearAllOnLogout()
    }
}
