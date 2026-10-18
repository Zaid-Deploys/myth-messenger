package com.myth.app.data.local

import com.myth.model.DeliveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val senderUsername: String,
    val recipientUsername: String,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.DELIVERED,
    val isReadByPeer: Boolean = false,
    val selfDestructCountdown: Int? = null
)

/**
 * STRICT MESSAGE NON-PERSISTENCE:
 * This store is 100% volatile and holds active messages only in memory.
 * No Room, SQLite, SharedPreferences, or filesystem caching is used.
 * When the 5-second self-destruct timer expires, messages are purged immediately.
 */
class VolatileChatStore(private val scope: CoroutineScope) {

    private val _conversations = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val conversations: StateFlow<Map<String, List<ChatMessage>>> = _conversations.asStateFlow()

    private val countdownJobs = ConcurrentHashMap<String, Job>()

    fun addMessage(msg: ChatMessage) {
        _conversations.update { map ->
            val list = map[msg.conversationId].orEmpty()
            map + (msg.conversationId to (list + msg))
        }
    }

    fun updateDeliveryStatus(messageId: String, status: DeliveryStatus) {
        _conversations.update { map ->
            map.mapValues { (_, list) ->
                list.map { msg ->
                    if (msg.id == messageId) msg.copy(deliveryStatus = status) else msg
                }
            }
        }
    }

    fun markReadByPeer(messageId: String) {
        _conversations.update { map ->
            map.mapValues { (_, list) ->
                list.map { msg ->
                    if (msg.id == messageId) msg.copy(isReadByPeer = true) else msg
                }
            }
        }
    }

    fun startSelfDestructCountdown(messageId: String, durationSeconds: Int = 5) {
        if (countdownJobs.containsKey(messageId)) return

        val job = scope.launch {
            for (remaining in durationSeconds downTo 0) {
                _conversations.update { map ->
                    map.mapValues { (_, list) ->
                        list.map { msg ->
                            if (msg.id == messageId) msg.copy(selfDestructCountdown = remaining) else msg
                        }
                    }
                }
                delay(1000)
            }
            // Expired: Purge permanently from in-memory state
            purgeMessage(messageId)
            countdownJobs.remove(messageId)
        }
        countdownJobs[messageId] = job
    }

    fun purgeMessage(messageId: String) {
        _conversations.update { map ->
            map.mapValues { (_, list) ->
                list.filterNot { it.id == messageId }
            }
        }
    }

    fun clearAllOnLogout() {
        countdownJobs.values.forEach { it.cancel() }
        countdownJobs.clear()
        _conversations.value = emptyMap()
    }
}
