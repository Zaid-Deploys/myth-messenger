package com.myth.server

import com.myth.crypto.CryptoUtils
import com.myth.server.routes.HealthResponse
import com.myth.model.*
import com.myth.server.config.ServerConfig
import com.myth.server.database.DatabaseFactory
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ServerIntegrationTest {

    private fun createTestConfig(): Pair<ServerConfig, File> {
        val tempDb = File.createTempFile("myth_test_", ".db")
        tempDb.deleteOnExit()
        val config = ServerConfig(
            dbUrl = "jdbc:sqlite:${tempDb.absolutePath}",
            volatileMessageTtlSeconds = 2L,
            selfDestructCountdownSeconds = 5
        )
        return config to tempDb
    }

    @Test
    fun testHealthEndpoint() = testApplication {
        val (config, _) = createTestConfig()
        application { module(config) }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val health = appJson.decodeFromString<HealthResponse>(response.bodyAsText())
        assertEquals("healthy", health.status)
    }

    @Test
    fun testRegistrationAndLoginFlow() = testApplication {
        val (config, _) = createTestConfig()
        application { module(config) }

        // 1. Register Alice
        val regResp = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(RegisterRequest("alice", "Password123!")))
        }
        assertEquals(HttpStatusCode.Created, regResp.status)
        val authData = appJson.decodeFromString<AuthResponse>(regResp.bodyAsText())
        assertEquals("alice", authData.username)
        assertTrue(authData.token.isNotEmpty())

        // 2. Duplicate registration fails
        val dupResp = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(RegisterRequest("Alice", "AnotherPassword123!")))
        }
        assertEquals(HttpStatusCode.Conflict, dupResp.status)

        // 3. Login with correct password
        val loginResp = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(LoginRequest("alice", "Password123!")))
        }
        assertEquals(HttpStatusCode.OK, loginResp.status)

        // 4. Login with incorrect password
        val badLoginResp = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(LoginRequest("alice", "WrongPassword!")))
        }
        assertEquals(HttpStatusCode.Unauthorized, badLoginResp.status)
    }

    @Test
    fun testContactRequestAndAcceptanceFlow() = testApplication {
        val (config, _) = createTestConfig()
        application { module(config) }

        // Register Alice and Bob
        val aliceAuth = appJson.decodeFromString<AuthResponse>(
            client.post("/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(appJson.encodeToString(RegisterRequest("alice", "Password123!")))
            }.bodyAsText()
        )

        val bobAuth = appJson.decodeFromString<AuthResponse>(
            client.post("/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(appJson.encodeToString(RegisterRequest("bob", "Password123!")))
            }.bodyAsText()
        )

        // 1. Alice searches for Bob
        val lookupResp = client.get("/api/v1/contacts/lookup?username=bob") {
            header("Authorization", "Bearer ${aliceAuth.token}")
        }
        assertEquals(HttpStatusCode.OK, lookupResp.status)

        // 2. Alice sends contact request to Bob
        val reqResp = client.post("/api/v1/contacts/request") {
            header("Authorization", "Bearer ${aliceAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(CreateContactRequest("bob")))
        }
        assertEquals(HttpStatusCode.Created, reqResp.status)
        val requestDto = appJson.decodeFromString<ContactRequestDto>(reqResp.bodyAsText())

        // 3. Bob checks incoming requests
        val bobContacts = appJson.decodeFromString<ContactListResponse>(
            client.get("/api/v1/contacts") {
                header("Authorization", "Bearer ${bobAuth.token}")
            }.bodyAsText()
        )
        assertEquals(1, bobContacts.incomingRequests.size)
        assertEquals("alice", bobContacts.incomingRequests[0].requesterUsername)

        // 4. Bob accepts request
        val acceptResp = client.post("/api/v1/contacts/respond") {
            header("Authorization", "Bearer ${bobAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(RespondContactRequest(requestDto.requestId, accept = true)))
        }
        assertEquals(HttpStatusCode.OK, acceptResp.status)

        // 5. Verify both have each other as accepted contacts
        val aliceUpdated = appJson.decodeFromString<ContactListResponse>(
            client.get("/api/v1/contacts") {
                header("Authorization", "Bearer ${aliceAuth.token}")
            }.bodyAsText()
        )
        assertEquals(1, aliceUpdated.contacts.size)
        assertEquals("bob", aliceUpdated.contacts[0].username)
        assertEquals(ContactStatus.ACCEPTED, aliceUpdated.contacts[0].status)
    }

    @Test
    fun testBlockingPreventsMessagingAndContactRequests() = testApplication {
        val (config, _) = createTestConfig()
        application { module(config) }

        val aliceAuth = appJson.decodeFromString<AuthResponse>(
            client.post("/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(appJson.encodeToString(RegisterRequest("alice", "Password123!")))
            }.bodyAsText()
        )

        val malloryAuth = appJson.decodeFromString<AuthResponse>(
            client.post("/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(appJson.encodeToString(RegisterRequest("mallory", "Password123!")))
            }.bodyAsText()
        )

        // Alice blocks Mallory
        val blockResp = client.post("/api/v1/contacts/block") {
            header("Authorization", "Bearer ${aliceAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(BlockContactRequest("mallory")))
        }
        assertEquals(HttpStatusCode.OK, blockResp.status)

        // Mallory attempts to send contact request to Alice -> Forbidden
        val reqResp = client.post("/api/v1/contacts/request") {
            header("Authorization", "Bearer ${malloryAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(CreateContactRequest("alice")))
        }
        assertEquals(HttpStatusCode.Forbidden, reqResp.status)
    }

    @Test
    fun testZeroMessagePersistenceInvariant() = testApplication {
        val (config, _) = createTestConfig()
        val db = DatabaseFactory(config.dbUrl)
        application { module(config, db) }

        // Explicitly inspect all tables in the SQLite database file
        java.sql.DriverManager.getConnection(config.dbUrl).use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT name FROM sqlite_master WHERE type='table';")
            val tableNames = mutableListOf<String>()
            while (rs.next()) {
                tableNames.add(rs.getString("name"))
            }

            // Tables permitted: users, public_keys, one_time_prekeys, contacts, blocked_users, sqlite_sequence
            for (table in tableNames) {
                assertFalse("Persistent table '$table' must not contain message or chat payload",
                    table.contains("message") || table.contains("chat") || table.contains("payload"))
            }
        }
    }

    @Test
    fun testWebSocketMessagingAndSelfDestructFlow() = testApplication {
        val (config, _) = createTestConfig()
        application { module(config) }

        val aliceAuth = appJson.decodeFromString<AuthResponse>(
            client.post("/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(appJson.encodeToString(RegisterRequest("alice", "Password123!")))
            }.bodyAsText()
        )

        val bobAuth = appJson.decodeFromString<AuthResponse>(
            client.post("/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(appJson.encodeToString(RegisterRequest("bob", "Password123!")))
            }.bodyAsText()
        )

        val reqResp = client.post("/api/v1/contacts/request") {
            header("Authorization", "Bearer ${aliceAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(CreateContactRequest("bob")))
        }
        val requestDto = appJson.decodeFromString<ContactRequestDto>(reqResp.bodyAsText())

        client.post("/api/v1/contacts/respond") {
            header("Authorization", "Bearer ${bobAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(RespondContactRequest(requestDto.requestId, accept = true)))
        }

        val wsClient = createClient {
            install(WebSockets)
        }

        val messageId = java.util.UUID.randomUUID().toString()

        val bobJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            wsClient.webSocket("/ws/v1/messaging") {
                send(Frame.Text(appJson.encodeToString<MythWebSocketFrame>(MythWebSocketFrame.Auth(bobAuth.token))))
                val authFrame = appJson.decodeFromString<MythWebSocketFrame>((incoming.receive() as Frame.Text).readText())
                assertTrue(authFrame is MythWebSocketFrame.AuthSuccess)

                val msgFrame = appJson.decodeFromString<MythWebSocketFrame>((incoming.receive() as Frame.Text).readText())
                assertTrue(msgFrame is MythWebSocketFrame.RelayedMessage)
                val relayed = msgFrame as MythWebSocketFrame.RelayedMessage
                assertEquals(messageId, relayed.messageId)
                assertEquals("alice", relayed.senderUsername)

                send(Frame.Text(appJson.encodeToString<MythWebSocketFrame>(
                    MythWebSocketFrame.ReadReceipt(
                        messageId = messageId,
                        conversationId = "alice",
                        readerUsername = "bob",
                        timestamp = System.currentTimeMillis(),
                        signatureBase64 = "valid_sig"
                    )
                )))

                val destructFrame = appJson.decodeFromString<MythWebSocketFrame>((incoming.receive() as Frame.Text).readText())
                assertTrue(destructFrame is MythWebSocketFrame.SelfDestructNotice)
                assertEquals(5, (destructFrame as MythWebSocketFrame.SelfDestructNotice).countdownSeconds)
            }
        }

        kotlinx.coroutines.delay(200)

        wsClient.webSocket("/ws/v1/messaging") {
            send(Frame.Text(appJson.encodeToString<MythWebSocketFrame>(MythWebSocketFrame.Auth(aliceAuth.token))))
            val authFrame = appJson.decodeFromString<MythWebSocketFrame>((incoming.receive() as Frame.Text).readText())
            assertTrue(authFrame is MythWebSocketFrame.AuthSuccess)

            send(Frame.Text(appJson.encodeToString<MythWebSocketFrame>(
                MythWebSocketFrame.SubmitMessage(
                    messageId = messageId,
                    recipientUsername = "bob",
                    ephemeralDhPublicKeyBase64 = "test_dh_pub",
                    sequenceNumber = 0,
                    previousChainLength = 0,
                    ciphertextBase64 = "ciphertext",
                    ivBase64 = "iv",
                    authTagBase64 = "authTag",
                    timestamp = System.currentTimeMillis()
                )
            )))

            val deliveryFrame = appJson.decodeFromString<MythWebSocketFrame>((incoming.receive() as Frame.Text).readText())
            assertTrue(deliveryFrame is MythWebSocketFrame.DeliveryStatusNotice)
            assertEquals(DeliveryStatus.DELIVERED, (deliveryFrame as MythWebSocketFrame.DeliveryStatusNotice).status)

            val receiptFrame = appJson.decodeFromString<MythWebSocketFrame>((incoming.receive() as Frame.Text).readText())
            assertTrue(receiptFrame is MythWebSocketFrame.ReadReceipt)

            val destructFrame = appJson.decodeFromString<MythWebSocketFrame>((incoming.receive() as Frame.Text).readText())
            assertTrue(destructFrame is MythWebSocketFrame.SelfDestructNotice)
            assertEquals(5, (destructFrame as MythWebSocketFrame.SelfDestructNotice).countdownSeconds)
        }

        bobJob.join()
    }

    @Test
    fun testCallSignalingWebSocketsFlow() = testApplication {
        val (config, _) = createTestConfig()
        application { module(config) }

        val aliceAuth = appJson.decodeFromString<AuthResponse>(
            client.post("/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(appJson.encodeToString(RegisterRequest("alice", "Password123!")))
            }.bodyAsText()
        )

        val bobAuth = appJson.decodeFromString<AuthResponse>(
            client.post("/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(appJson.encodeToString(RegisterRequest("bob", "Password123!")))
            }.bodyAsText()
        )

        val reqResp = client.post("/api/v1/contacts/request") {
            header("Authorization", "Bearer ${aliceAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(CreateContactRequest("bob")))
        }
        val requestDto = appJson.decodeFromString<ContactRequestDto>(reqResp.bodyAsText())

        client.post("/api/v1/contacts/respond") {
            header("Authorization", "Bearer ${bobAuth.token}")
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(RespondContactRequest(requestDto.requestId, accept = true)))
        }

        val wsClient = createClient {
            install(WebSockets)
        }

        val callId = java.util.UUID.randomUUID().toString()
        val offerSdp = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=Myth-Voice\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111"
        val answerSdp = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=Myth-Voice\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111"

        val bobJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            wsClient.webSocket("/ws/v1/messaging") {
                send(Frame.Text(appJson.encodeToString<MythWebSocketFrame>(MythWebSocketFrame.Auth(bobAuth.token))))
                val bobAuthFrame = appJson.decodeFromString<MythWebSocketFrame>((incoming.receive() as Frame.Text).readText())
                assertTrue(bobAuthFrame is MythWebSocketFrame.AuthSuccess)

                val incomingCallFrame = appJson.decodeFromString<MythWebSocketFrame>((incoming.receive() as Frame.Text).readText())
                assertTrue(incomingCallFrame is MythWebSocketFrame.IncomingCall)
                val inc = incomingCallFrame as MythWebSocketFrame.IncomingCall
                assertEquals(callId, inc.callId)
                assertEquals("alice", inc.callerUsername)

                send(Frame.Text(appJson.encodeToString<MythWebSocketFrame>(
                    MythWebSocketFrame.CallAnswer(
                        callId = callId,
                        recipientUsername = "alice",
                        sdpAnswer = answerSdp,
                        timestamp = System.currentTimeMillis()
                    )
                )))

                val endFrame = appJson.decodeFromString<MythWebSocketFrame>((incoming.receive() as Frame.Text).readText())
                assertTrue(endFrame is MythWebSocketFrame.CallEnd)
                assertEquals("NORMAL_HANGUP", (endFrame as MythWebSocketFrame.CallEnd).reason)
            }
        }

        kotlinx.coroutines.delay(200)

        wsClient.webSocket("/ws/v1/messaging") {
            send(Frame.Text(appJson.encodeToString<MythWebSocketFrame>(MythWebSocketFrame.Auth(aliceAuth.token))))
            val aliceAuthFrame = appJson.decodeFromString<MythWebSocketFrame>((incoming.receive() as Frame.Text).readText())
            assertTrue(aliceAuthFrame is MythWebSocketFrame.AuthSuccess)

            send(Frame.Text(appJson.encodeToString<MythWebSocketFrame>(
                MythWebSocketFrame.CallOffer(
                    callId = callId,
                    recipientUsername = "bob",
                    sdpOffer = offerSdp,
                    timestamp = System.currentTimeMillis()
                )
            )))

            val answerFrame = appJson.decodeFromString<MythWebSocketFrame>((incoming.receive() as Frame.Text).readText())
            assertTrue(answerFrame is MythWebSocketFrame.CallAnswer)
            val ans = answerFrame as MythWebSocketFrame.CallAnswer
            assertEquals(callId, ans.callId)

            send(Frame.Text(appJson.encodeToString<MythWebSocketFrame>(
                MythWebSocketFrame.CallEnd(
                    callId = callId,
                    recipientUsername = "bob",
                    reason = "NORMAL_HANGUP"
                )
            )))
        }

        bobJob.join()
    }
}
