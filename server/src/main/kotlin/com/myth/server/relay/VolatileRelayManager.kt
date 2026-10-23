package com.myth.server.relay

import com.myth.crypto.CryptoUtils
import com.myth.model.DeliveryStatus
import com.myth.model.MythWebSocketFrame
import com.myth.server.database.DatabaseFactory
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class VolatileMessage(
    val messageId: String,
    val senderUsername: String,
    val recipientUsername: String,
    val frame: MythWebSocketFrame.RelayedMessage,
    val createdAt: Long,
    val expiresAt: Long
)

class VolatileRelayManager(
    private val db: DatabaseFactory,
    private val scope: CoroutineScope,
    private val json: Json,
    private val ttlSeconds: Long = 60L,
    private val selfDestructSeconds: Int = 5
) {
    // Map of active username -> WebSocket session
    private val activeSessions = ConcurrentHashMap<String, DefaultWebSocketSession>()

    // Bounded in-memory queues for volatile buffering (strictly ephemeral, max 10 per user)
    private val volatileQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<VolatileMessage>>()
    private val MAX_QUEUE_PER_USER = 10

    init {
        // Background volatile cleanup worker (runs every 5 seconds)
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(5000)
                cleanupExpiredVolatileMessages()
            }
        }
    }

    fun registerSession(username: String, session: DefaultWebSocketSession) {
        val normalized = username.lowercase().trim()
        activeSessions[normalized] = session

        // Flush any buffered volatile messages for this recipient
        val queue = volatileQueues[normalized]
        if (queue != null) {
            scope.launch {
                while (true) {
                    val msg = queue.poll() ?: break
                    if (System.currentTimeMillis() <= msg.expiresAt) {
                        sendFrame(normalized, msg.frame)
                        // Acknowledge delivery to sender
                        notifyDeliveryStatus(msg.senderUsername, msg.messageId, DeliveryStatus.DELIVERED)
                    }
                }
            }
        }
    }

    fun unregisterSession(username: String, session: DefaultWebSocketSession) {
        val normalized = username.lowercase().trim()
        activeSessions.remove(normalized, session)
    }

    suspend fun handleSubmittedMessage(senderUsername: String, submit: MythWebSocketFrame.SubmitMessage) {
        val sender = senderUsername.lowercase().trim()
        val recipient = submit.recipientUsername.lowercase().trim()

        // 1. Authorization check: Are they accepted contacts?
        if (!db.areAcceptedContacts(sender, recipient)) {
            sendFrame(sender, MythWebSocketFrame.Error("UNAUTHORIZED", "Cannot message user: not an accepted contact"))
            return
        }

        // 2. Block check: Is either user blocked?
        val senderUser = db.getUserByUsername(sender)
        val recipientUser = db.getUserByUsername(recipient)
        if (senderUser == null || recipientUser == null || db.isBlocked(senderUser.id, recipientUser.id)) {
            sendFrame(sender, MythWebSocketFrame.Error("BLOCKED", "Cannot message user: communication is blocked"))
            return
        }

        val now = System.currentTimeMillis()
        val relayed = MythWebSocketFrame.RelayedMessage(
            messageId = submit.messageId,
            senderUsername = sender,
            ephemeralDhPublicKeyBase64 = submit.ephemeralDhPublicKeyBase64,
            sequenceNumber = submit.sequenceNumber,
            previousChainLength = submit.previousChainLength,
            ciphertextBase64 = submit.ciphertextBase64,
            ivBase64 = submit.ivBase64,
            authTagBase64 = submit.authTagBase64,
            timestamp = submit.timestamp
        )

        val recipientSession = activeSessions[recipient]
        if (recipientSession != null) {
            // Forward immediately to recipient's active WebSocket
            sendFrame(recipient, relayed)
            notifyDeliveryStatus(sender, submit.messageId, DeliveryStatus.DELIVERED)
        } else {
            // Buffer in volatile RAM queue for max ttlSeconds
            val userQueue = volatileQueues.computeIfAbsent(recipient) { ConcurrentLinkedQueue() }
            if (userQueue.size >= MAX_QUEUE_PER_USER) {
                // Drop oldest buffered message to prevent unbounded memory growth
                userQueue.poll()
            }
            userQueue.add(
                VolatileMessage(
                    messageId = submit.messageId,
                    senderUsername = sender,
                    recipientUsername = recipient,
                    frame = relayed,
                    createdAt = now,
                    expiresAt = now + (ttlSeconds * 1000L)
                )
            )
        }
    }

    suspend fun handleReadReceipt(readerUsername: String, receipt: MythWebSocketFrame.ReadReceipt) {
        val reader = readerUsername.lowercase().trim()

        // Forward read receipt to other participant if active
        // Determine recipient of the receipt from the conversation contacts
        for ((username, session) in activeSessions) {
            if (username != reader && db.areAcceptedContacts(reader, username)) {
                sendFrame(username, receipt)
                // Trigger synchronized 5-second self-destruct notice to both participants
                val notice = MythWebSocketFrame.SelfDestructNotice(
                    messageId = receipt.messageId,
                    conversationId = receipt.conversationId,
                    countdownSeconds = selfDestructSeconds,
                    timestamp = System.currentTimeMillis()
                )
                sendFrame(username, notice)
                sendFrame(reader, notice)
            }
        }
    }

    suspend fun handleScreenshotAlert(actorUsername: String, alert: MythWebSocketFrame.ScreenshotAlert) {
        val actor = actorUsername.lowercase().trim()
        for ((username, _) in activeSessions) {
            if (username != actor && db.areAcceptedContacts(actor, username)) {
                sendFrame(username, alert)
            }
        }
    }

    suspend fun handleCallOffer(callerUsername: String, offer: MythWebSocketFrame.CallOffer) {
        val caller = callerUsername.lowercase().trim()
        val recipient = offer.recipientUsername.lowercase().trim()

        if (!db.areAcceptedContacts(caller, recipient)) {
            sendFrame(caller, MythWebSocketFrame.CallReject(offer.callId, caller, "UNAUTHORIZED"))
            return
        }

        val callerUser = db.getUserByUsername(caller)
        val recipientUser = db.getUserByUsername(recipient)
        if (callerUser == null || recipientUser == null || db.isBlocked(callerUser.id, recipientUser.id)) {
            sendFrame(caller, MythWebSocketFrame.CallReject(offer.callId, caller, "BLOCKED"))
            return
        }

        val recipientSession = activeSessions[recipient]
        if (recipientSession != null) {
            val incoming = MythWebSocketFrame.IncomingCall(
                callId = offer.callId,
                callerUsername = caller,
                sdpOffer = offer.sdpOffer,
                timestamp = offer.timestamp
            )
            sendFrame(recipient, incoming)
        } else {
            // Recipient offline -> notify caller immediately
            sendFrame(caller, MythWebSocketFrame.CallReject(offer.callId, caller, "UNAVAILABLE"))
        }
    }

    suspend fun handleCallAnswer(answererUsername: String, answer: MythWebSocketFrame.CallAnswer) {
        val answerer = answererUsername.lowercase().trim()
        val recipient = answer.recipientUsername.lowercase().trim()
        if (db.areAcceptedContacts(answerer, recipient)) {
            sendFrame(recipient, answer)
        }
    }

    suspend fun handleIceCandidate(senderUsername: String, candidate: MythWebSocketFrame.IceCandidate) {
        val recipient = candidate.recipientUsername.lowercase().trim()
        sendFrame(recipient, candidate)
    }

    suspend fun handleCallReject(rejecterUsername: String, reject: MythWebSocketFrame.CallReject) {
        val rejecter = rejecterUsername.lowercase().trim()
        val recipient = reject.recipientUsername.lowercase().trim()
        sendFrame(recipient, reject)
    }

    suspend fun handleCallEnd(enderUsername: String, end: MythWebSocketFrame.CallEnd) {
        val ender = enderUsername.lowercase().trim()
        val recipient = end.recipientUsername.lowercase().trim()
        sendFrame(recipient, end)
    }

    private suspend fun notifyDeliveryStatus(sender: String, messageId: String, status: DeliveryStatus, reason: String? = null) {
        sendFrame(
            sender,
            MythWebSocketFrame.DeliveryStatusNotice(
                messageId = messageId,
                status = status,
                reason = reason,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun sendFrame(username: String, frame: MythWebSocketFrame) {
        val session = activeSessions[username] ?: return
        try {
            val text = json.encodeToString(frame)
            session.send(Frame.Text(text))
        } catch (e: Exception) {
            activeSessions.remove(username, session)
        }
    }

    private suspend fun cleanupExpiredVolatileMessages() {
        val now = System.currentTimeMillis()
        for ((recipient, queue) in volatileQueues) {
            val iter = queue.iterator()
            while (iter.hasNext()) {
                val msg = iter.next()
                if (now > msg.expiresAt) {
                    iter.remove()
                    // Truthful notification: sender informed that undelivered message expired
                    notifyDeliveryStatus(msg.senderUsername, msg.messageId, DeliveryStatus.EXPIRED, "Message expired without delivery")
                }
            }
            if (queue.isEmpty()) {
                volatileQueues.remove(recipient, queue)
            }
        }
    }
}
