package com.myth.app.domain

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.myth.app.data.remote.MythWebSocketClient
import com.myth.model.MythWebSocketFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

sealed class CallState {
    object Idle : CallState()
    data class Outgoing(val contactUsername: String, val callId: String) : CallState()
    data class Incoming(val callerUsername: String, val callId: String, val sdpOffer: String) : CallState()
    data class Connecting(val contactUsername: String, val callId: String) : CallState()
    data class Active(val contactUsername: String, val callId: String, val durationSeconds: Long) : CallState()
    data class Ended(val reason: String) : CallState()
}

class CallManager(
    private val wsClient: MythWebSocketClient,
    private val scope: CoroutineScope
) {
    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private var durationJob: Job? = null
    var currentUser: String? = null

    init {
        scope.launch(Dispatchers.Default) {
            wsClient.incomingFrames.collect { frame ->
                handleCallFrame(frame)
            }
        }
    }

    fun startCall(targetUsername: String): String {
        val user = checkNotNull(currentUser) { "User must be authenticated to call" }
        val callId = UUID.randomUUID().toString()
        val dummySdpOffer = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=Myth-WebRTC-Voice\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2"

        val offerFrame = MythWebSocketFrame.CallOffer(
            callId = callId,
            recipientUsername = targetUsername,
            sdpOffer = dummySdpOffer,
            timestamp = System.currentTimeMillis()
        )
        wsClient.sendFrame(offerFrame)
        _callState.value = CallState.Outgoing(targetUsername, callId)
        return callId
    }

    fun acceptCall(context: Context) {
        val state = _callState.value
        if (state !is CallState.Incoming) return

        val dummySdpAnswer = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=Myth-WebRTC-Voice\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2"
        val answerFrame = MythWebSocketFrame.CallAnswer(
            callId = state.callId,
            recipientUsername = state.callerUsername,
            sdpAnswer = dummySdpAnswer,
            timestamp = System.currentTimeMillis()
        )
        wsClient.sendFrame(answerFrame)
        startAudioSession(context)
        transitionToActive(state.callerUsername, state.callId)
    }

    fun rejectCall(reason: String = "DECLINED") {
        val state = _callState.value
        val (targetUser, callId) = when (state) {
            is CallState.Incoming -> state.callerUsername to state.callId
            is CallState.Outgoing -> state.contactUsername to state.callId
            else -> return
        }

        wsClient.sendFrame(MythWebSocketFrame.CallReject(callId, targetUser, reason))
        endAudioSession()
        _callState.value = CallState.Ended(reason)
        scope.launch {
            delay(1500)
            _callState.value = CallState.Idle
        }
    }

    fun endCall() {
        val state = _callState.value
        val (targetUser, callId) = when (state) {
            is CallState.Active -> state.contactUsername to state.callId
            is CallState.Connecting -> state.contactUsername to state.callId
            is CallState.Outgoing -> state.contactUsername to state.callId
            is CallState.Incoming -> state.callerUsername to state.callId
            else -> return
        }

        wsClient.sendFrame(MythWebSocketFrame.CallEnd(callId, targetUser, "NORMAL_HANGUP"))
        endAudioSession()
        _callState.value = CallState.Ended("Call Ended")
        scope.launch {
            delay(1500)
            _callState.value = CallState.Idle
        }
    }

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
    }

    fun toggleSpeaker(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val newSpeakerState = !_isSpeakerOn.value
        audioManager.isSpeakerphoneOn = newSpeakerState
        _isSpeakerOn.value = newSpeakerState
    }

    private fun startAudioSession(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = _isMuted.value
        audioManager.isSpeakerphoneOn = _isSpeakerOn.value
    }

    private fun endAudioSession() {
        durationJob?.cancel()
        durationJob = null
        _isMuted.value = false
        _isSpeakerOn.value = false
    }

    private fun transitionToActive(contactUsername: String, callId: String) {
        _callState.value = CallState.Active(contactUsername, callId, 0L)
        durationJob?.cancel()
        durationJob = scope.launch(Dispatchers.Default) {
            var seconds = 0L
            while (isActive) {
                delay(1000)
                seconds++
                val cur = _callState.value
                if (cur is CallState.Active) {
                    _callState.value = cur.copy(durationSeconds = seconds)
                }
            }
        }
    }

    private fun handleCallFrame(frame: MythWebSocketFrame) {
        when (frame) {
            is MythWebSocketFrame.IncomingCall -> {
                if (_callState.value is CallState.Idle) {
                    _callState.value = CallState.Incoming(
                        callerUsername = frame.callerUsername,
                        callId = frame.callId,
                        sdpOffer = frame.sdpOffer
                    )
                } else {
                    // Current user is busy on another call
                    wsClient.sendFrame(MythWebSocketFrame.CallReject(frame.callId, frame.callerUsername, "BUSY"))
                }
            }

            is MythWebSocketFrame.CallAnswer -> {
                val state = _callState.value
                if (state is CallState.Outgoing && state.callId == frame.callId) {
                    transitionToActive(state.contactUsername, state.callId)
                }
            }

            is MythWebSocketFrame.CallReject -> {
                val state = _callState.value
                if (state is CallState.Outgoing && state.callId == frame.callId) {
                    endAudioSession()
                    _callState.value = CallState.Ended(frame.reason)
                    scope.launch {
                        delay(2000)
                        _callState.value = CallState.Idle
                    }
                }
            }

            is MythWebSocketFrame.CallEnd -> {
                endAudioSession()
                _callState.value = CallState.Ended(frame.reason)
                scope.launch {
                    delay(1500)
                    _callState.value = CallState.Idle
                }
            }

            else -> {}
        }
    }
}
