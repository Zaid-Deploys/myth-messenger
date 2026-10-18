package com.myth.server.routes

import com.myth.model.MythWebSocketFrame
import com.myth.server.relay.VolatileRelayManager
import com.myth.server.security.TokenService
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.webSocketRoutes(
    tokenService: TokenService,
    relayManager: VolatileRelayManager,
    json: Json
) {
    webSocket("/ws/v1/messaging") {
        var authenticatedUser: String? = null

        try {
            // First frame must be an authentication frame
            val firstFrame = incoming.receive()
            if (firstFrame is Frame.Text) {
                val parsed = json.decodeFromString<MythWebSocketFrame>(firstFrame.readText())
                if (parsed is MythWebSocketFrame.Auth) {
                    val user = tokenService.validateToken(parsed.token)
                    if (user != null) {
                        authenticatedUser = user.username
                        relayManager.registerSession(user.username, this)
                        send(Frame.Text(json.encodeToString<MythWebSocketFrame>(MythWebSocketFrame.AuthSuccess(user.userId, user.username))))
                    } else {
                        send(Frame.Text(json.encodeToString<MythWebSocketFrame>(MythWebSocketFrame.Error("UNAUTHORIZED", "Invalid session token"))))
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                        return@webSocket
                    }
                } else {
                    send(Frame.Text(json.encodeToString<MythWebSocketFrame>(MythWebSocketFrame.Error("AUTH_REQUIRED", "Initial frame must be Auth"))))
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                    return@webSocket
                }
            }

            val username = checkNotNull(authenticatedUser)

            // Listen for frames
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val msg = try {
                        json.decodeFromString<MythWebSocketFrame>(frame.readText())
                    } catch (e: Exception) {
                        send(Frame.Text(json.encodeToString<MythWebSocketFrame>(MythWebSocketFrame.Error("MALFORMED_FRAME", "Could not parse WebSocket frame"))))
                        continue
                    }

                    when (msg) {
                        is MythWebSocketFrame.SubmitMessage -> {
                            relayManager.handleSubmittedMessage(username, msg)
                        }
                        is MythWebSocketFrame.ReadReceipt -> {
                            relayManager.handleReadReceipt(username, msg)
                        }
                        is MythWebSocketFrame.ScreenshotAlert -> {
                            relayManager.handleScreenshotAlert(username, msg)
                        }
                        is MythWebSocketFrame.CallOffer -> {
                            relayManager.handleCallOffer(username, msg)
                        }
                        is MythWebSocketFrame.CallAnswer -> {
                            relayManager.handleCallAnswer(username, msg)
                        }
                        is MythWebSocketFrame.IceCandidate -> {
                            relayManager.handleIceCandidate(username, msg)
                        }
                        is MythWebSocketFrame.CallReject -> {
                            relayManager.handleCallReject(username, msg)
                        }
                        is MythWebSocketFrame.CallEnd -> {
                            relayManager.handleCallEnd(username, msg)
                        }
                        is MythWebSocketFrame.Heartbeat -> {
                            send(Frame.Text(json.encodeToString<MythWebSocketFrame>(MythWebSocketFrame.Heartbeat(System.currentTimeMillis()))))
                        }
                        else -> {
                            // Ignore unrecognized or server-to-client frames
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Socket disconnected
        } finally {
            authenticatedUser?.let {
                relayManager.unregisterSession(it, this)
            }
        }
    }
}
