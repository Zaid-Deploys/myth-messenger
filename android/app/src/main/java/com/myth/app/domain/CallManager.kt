package com.myth.app.domain

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.myth.app.data.remote.MythWebSocketClient
import com.myth.crypto.CryptoUtils
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
    data class Active(val contactUsername: String, val callId: String) : CallState()
    data class Ended(val reason: String) : CallState()
}

class CallManager(
    private val context: Context,
    private val wsClient: MythWebSocketClient,
    private val scope: CoroutineScope
) {
    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private var durationJob: Job? = null
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    var currentUser: String? = null

    private val sampleRate = 8000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

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

    fun acceptCall(ctx: Context? = null) {
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
        val newMute = !_isMuted.value
        _isMuted.value = newMute
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isMicrophoneMute = newMute
    }

    fun toggleSpeaker(ctx: Context? = null) {
        val targetContext = ctx ?: context
        val audioManager = targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val newSpeakerState = !_isSpeakerOn.value
        audioManager.isSpeakerphoneOn = newSpeakerState
        _isSpeakerOn.value = newSpeakerState
    }

    private fun startAudioPlayback() {
        try {
            val minBufOut = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioEncoding)
            val bufferSize = minBufOut.coerceAtLeast(3200)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioEncoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfigOut)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track.play()
            audioTrack = track
            android.util.Log.d("CallManager", "AudioTrack initialized and playing")
        } catch (e: Exception) {
            android.util.Log.e("CallManager", "Failed to initialize AudioTrack", e)
        }
    }

    private fun startAudioRecording(targetUsername: String, callId: String) {
        var record: AudioRecord? = null
        try {
            val minBufIn = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioEncoding)
            val bufferSize = minBufIn.coerceAtLeast(3200)

            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfigIn,
                    audioEncoding,
                    bufferSize
                )
            } catch (e: Exception) {
                android.util.Log.w("CallManager", "VOICE_COMMUNICATION source failed, falling back to MIC", e)
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfigIn,
                    audioEncoding,
                    bufferSize
                )
            }

            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record.startRecording()
                audioRecord = record
                android.util.Log.d("CallManager", "Audio recording started at 8000Hz")

                val chunkBytes = 1600 // 100ms chunks (800 16-bit samples)
                val buffer = ByteArray(chunkBytes)
                var seq = 0

                while (scope.isActive && _callState.value is CallState.Active) {
                    val bytesRead = record.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        if (!_isMuted.value) {
                            val data = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                            val base64 = CryptoUtils.toBase64(data)
                            wsClient.sendFrame(
                                MythWebSocketFrame.IceCandidate(
                                    callId = callId,
                                    recipientUsername = targetUsername,
                                    sdpMid = "audio",
                                    sdpMLineIndex = seq++,
                                    sdpCandidate = base64
                                )
                            )
                        }
                    }
                }
            } else {
                android.util.Log.e("CallManager", "AudioRecord failed to initialize")
            }
        } catch (e: SecurityException) {
            android.util.Log.e("CallManager", "RECORD_AUDIO runtime permission not granted", e)
        } catch (e: Exception) {
            android.util.Log.e("CallManager", "Error in AudioRecord capture loop", e)
        } finally {
            try {
                record?.stop()
                record?.release()
            } catch (e: Exception) {}
            audioRecord = null
        }
    }

    private fun startAudioSession(targetUsername: String, callId: String) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = _isMuted.value
        audioManager.isSpeakerphoneOn = _isSpeakerOn.value

        startAudioPlayback()

        recordingJob?.cancel()
        recordingJob = scope.launch(Dispatchers.IO) {
            startAudioRecording(targetUsername, callId)
        }
    }

    fun restartAudioCapture() {
        val state = _callState.value
        if (state is CallState.Active) {
            recordingJob?.cancel()
            recordingJob = scope.launch(Dispatchers.IO) {
                startAudioRecording(state.contactUsername, state.callId)
            }
        }
    }

    private fun endAudioSession() {
        durationJob?.cancel()
        durationJob = null
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isMicrophoneMute = false
            audioManager.isSpeakerphoneOn = false
        } catch (e: Exception) {}

        _isMuted.value = false
        _isSpeakerOn.value = false
        _callDurationSeconds.value = 0L
    }

    private fun transitionToActive(contactUsername: String, callId: String) {
        _callState.value = CallState.Active(contactUsername, callId)
        _callDurationSeconds.value = 0L

        startAudioSession(contactUsername, callId)

        durationJob?.cancel()
        durationJob = scope.launch(Dispatchers.Default) {
            var seconds = 0L
            while (isActive) {
                delay(1000)
                seconds++
                _callDurationSeconds.value = seconds
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

            is MythWebSocketFrame.IceCandidate -> {
                if (frame.sdpMid == "audio" && _callState.value is CallState.Active) {
                    try {
                        val pcm = CryptoUtils.fromBase64(frame.sdpCandidate)
                        audioTrack?.write(pcm, 0, pcm.size)
                    } catch (e: Exception) {
                        android.util.Log.e("CallManager", "Error playing incoming audio frame", e)
                    }
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
