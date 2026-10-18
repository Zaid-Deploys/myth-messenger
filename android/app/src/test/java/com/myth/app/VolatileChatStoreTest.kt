package com.myth.app

import com.myth.app.data.local.ChatMessage
import com.myth.app.data.local.VolatileChatStore
import com.myth.model.DeliveryStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VolatileChatStoreTest {

    @Test
    fun testVolatileChatStorageAndSelfDestruction() = runTest {
        val store = VolatileChatStore(this)

        val msg = ChatMessage(
            id = "msg-123",
            conversationId = "bob",
            senderUsername = "alice",
            recipientUsername = "bob",
            text = "Self-destructing text",
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            deliveryStatus = DeliveryStatus.DELIVERED
        )

        // 1. Add message to memory
        store.addMessage(msg)
        val initialMap = store.conversations.value
        assertEquals(1, initialMap["bob"]?.size)
        assertEquals("msg-123", initialMap["bob"]?.first()?.id)

        // 2. Mark read by peer -> start 5-second countdown
        store.markReadByPeer("msg-123")
        store.startSelfDestructCountdown("msg-123", durationSeconds = 5)

        // Advance 2 seconds
        advanceTimeBy(2000)
        val midMap = store.conversations.value
        assertTrue("Message should still exist during countdown", midMap["bob"]?.any { it.id == "msg-123" } == true)

        // Advance until countdown completes and message is purged
        advanceUntilIdle()
        val expiredMap = store.conversations.value
        assertFalse("Message must be permanently purged from memory after countdown", expiredMap["bob"]?.any { it.id == "msg-123" } == true)
    }

    @Test
    fun testClearAllOnLogout() = runTest {
        val store = VolatileChatStore(this)

        store.addMessage(
            ChatMessage(
                id = "msg-1",
                conversationId = "bob",
                senderUsername = "alice",
                recipientUsername = "bob",
                text = "Secret 1",
                timestamp = System.currentTimeMillis(),
                isOutgoing = true
            )
        )
        store.addMessage(
            ChatMessage(
                id = "msg-2",
                conversationId = "charlie",
                senderUsername = "alice",
                recipientUsername = "charlie",
                text = "Secret 2",
                timestamp = System.currentTimeMillis(),
                isOutgoing = true
            )
        )

        assertEquals(2, store.conversations.value.size)

        // Log out -> all active memory wiped
        store.clearAllOnLogout()
        assertTrue("Memory must be empty after logout", store.conversations.value.isEmpty())
    }
}
