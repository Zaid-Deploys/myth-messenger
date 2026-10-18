package com.myth.app.data.remote

import com.myth.model.MythWebSocketFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    ERROR
}

class MythWebSocketClient(
    private val wsUrl: String = "ws://10.0.2.2:8080/ws/v1/messaging",
    private val scope: CoroutineScope
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<MythWebSocketFrame>(extraBufferCapacity = 64)
    val incomingFrames: SharedFlow<MythWebSocketFrame> = _incomingFrames.asSharedFlow()

    fun connect(sessionToken: String) {
        if (_connectionStatus.value == ConnectionStatus.CONNECTED || _connectionStatus.value == ConnectionStatus.AUTHENTICATED) {
            return
        }

        _connectionStatus.value = ConnectionStatus.CONNECTING
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                _connectionStatus.value = ConnectionStatus.CONNECTED
                // Immediately authenticate with session token
                val authFrame = MythWebSocketFrame.Auth(sessionToken)
                sendFrame(authFrame)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val frame = json.decodeFromString<MythWebSocketFrame>(text)
                    if (frame is MythWebSocketFrame.AuthSuccess) {
                        _connectionStatus.value = ConnectionStatus.AUTHENTICATED
                    }
                    scope.launch(Dispatchers.Default) {
                        _incomingFrames.emit(frame)
                    }
                } catch (e: Exception) {
                    // Ignore malformed frame
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _connectionStatus.value = ConnectionStatus.ERROR
            }
        })
    }

    fun sendFrame(frame: MythWebSocketFrame): Boolean {
        val ws = webSocket ?: return false
        val text = json.encodeToString(frame)
        return ws.send(text)
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }
}
