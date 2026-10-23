package com.myth.app.domain

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
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
import kotlin.math.abs
import kotlin.math.sqrt

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

    // Hardware Audio Effects
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

    // Ringtones
    private var incomingRingtone: Ringtone? = null
    private var outgoingToneGenerator: ToneGenerator? = null

    // Real-time latency tracking for AudioTrack
    @Volatile
    private var totalWrittenFrames: Long = 0L

    var currentUser: String? = null

    // High fidelity Wideband HD Voice (16kHz PCM 16-bit Mono)
    private val sampleRate = 16000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    // 30ms chunk at 16kHz = 480 samples = 960 bytes
    private val chunkBytes = 960

    // Noise gate and Voice Activity Detection (VAD) thresholds
    private val noiseGateRmsThreshold = 280.0
    private val voiceHangoverChunks = 4

    init {
        scope.launch(Dispatchers.Default) {
            wsClient.incomingFrames.collect { frame ->
                handleCallFrame(frame)
            }
        }
    }

    fun startCall(targetUsername: String): String {
        checkNotNull(currentUser) { "User must be authenticated to call" }
        val callId = UUID.randomUUID().toString()
        val dummySdpOffer = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=Myth-Voice\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2"

        val offerFrame = MythWebSocketFrame.CallOffer(
            callId = callId,
            recipientUsername = targetUsername,
            sdpOffer = dummySdpOffer,
            timestamp = System.currentTimeMillis()
        )
        wsClient.sendFrame(offerFrame)
        _callState.value = CallState.Outgoing(targetUsername, callId)

        startRingbackTone()
        return callId
    }

    fun acceptCall(ctx: Context? = null) {
        val state = _callState.value
        if (state !is CallState.Incoming) return

        stopIncomingRingtone()
        stopRingbackTone()

        val dummySdpAnswer = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=Myth-Voice\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2"
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
        stopIncomingRingtone()
        stopRingbackTone()

        val state = _callState.value
        val (targetUser, callId) = when (state) {
            is CallState.Incoming -> state.callerUsername to state.callId
            is CallState.Outgoing -> state.contactUsername to state.callId
            else -> {
                endAudioSession()
                _callState.value = CallState.Idle
                return
            }
        }

        wsClient.sendFrame(MythWebSocketFrame.CallReject(callId, targetUser, reason))
        endAudioSession()
        _callState.value = CallState.Idle
    }

    fun endCall() {
        stopIncomingRingtone()
        stopRingbackTone()

        val state = _callState.value
        val (targetUser, callId) = when (state) {
            is CallState.Active -> state.contactUsername to state.callId
            is CallState.Connecting -> state.contactUsername to state.callId
            is CallState.Outgoing -> state.contactUsername to state.callId
            is CallState.Incoming -> state.callerUsername to state.callId
            else -> {
                endAudioSession()
                _callState.value = CallState.Idle
                return
            }
        }

        wsClient.sendFrame(MythWebSocketFrame.CallEnd(callId, targetUser, "NORMAL_HANGUP"))
        endAudioSession()
        _callState.value = CallState.Ended("Call Ended")
        scope.launch {
            delay(600)
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

    private fun startIncomingRingtone() {
        try {
            stopIncomingRingtone()
            val ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            incomingRingtone = RingtoneManager.getRingtone(context, ringtoneUri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
            android.util.Log.d("CallManager", "Incoming call ringtone started")
        } catch (e: Exception) {
            android.util.Log.e("CallManager", "Failed to play incoming ringtone", e)
        }
    }

    private fun stopIncomingRingtone() {
        try {
            incomingRingtone?.stop()
        } catch (e: Exception) {}
            incomingRingtone = null
    }

    private fun startRingbackTone() {
        try {
            stopRingbackTone()
            outgoingToneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80).apply {
                startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
            android.util.Log.d("CallManager", "Outgoing ringback tone started")
        } catch (e: Exception) {
            android.util.Log.e("CallManager", "Failed to play ringback tone", e)
        }
    }

    private fun stopRingbackTone() {
        try {
            outgoingToneGenerator?.stopTone()
            outgoingToneGenerator?.release()
        } catch (e: Exception) {}
        outgoingToneGenerator = null
    }

    private fun startAudioPlayback() {
        try {
            val minBufOut = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioEncoding)
            val bufferSize = (minBufOut * 2).coerceAtLeast(1920)

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
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            track.play()
            audioTrack = track
            totalWrittenFrames = 0L
            android.util.Log.d("CallManager", "Low-latency AudioTrack initialized and playing at 16kHz")
        } catch (e: Exception) {
            android.util.Log.e("CallManager", "Failed to initialize AudioTrack", e)
        }
    }

    /**
     * Filters raw 16-bit PCM:
     * - Suppresses low-level ambient mic hiss (< 180 amplitude)
     * - Returns true if RMS exceeds voice energy threshold
     */
    private fun filterChunkAndDetectVoice(buffer: ByteArray, length: Int): Boolean {
        var sumSquares = 0.0
        val sampleCount = length / 2
        if (sampleCount == 0) return false

        for (i in 0 until length step 2) {
            val sampleVal = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort()
            sumSquares += sampleVal.toDouble() * sampleVal.toDouble()

            // Soft noise floor cutoff to clean background fuzz
            val cleaned = if (abs(sampleVal.toInt()) < 180) 0.toShort() else sampleVal
            buffer[i] = (cleaned.toInt() and 0xFF).toByte()
            buffer[i + 1] = ((cleaned.toInt() shr 8) and 0xFF).toByte()
        }

        val rms = sqrt(sumSquares / sampleCount)
        return rms >= noiseGateRmsThreshold
    }

    private fun startAudioRecording(targetUsername: String, callId: String) {
        var record: AudioRecord? = null
        try {
            val minBufIn = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioEncoding)
            val bufferSize = (minBufIn * 2).coerceAtLeast(1920)

            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfigIn,
                    audioEncoding,
                    bufferSize
                )
            } catch (e: Exception) {
                android.util.Log.w("CallManager", "VOICE_COMMUNICATION audio source failed, falling back to MIC", e)
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
                val audioSessionId = record.audioSessionId

                // Enable hardware Noise Suppression if available
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                            enabled = true
                        }
                        android.util.Log.d("CallManager", "Hardware NoiseSuppressor active")
                    } catch (e: Exception) {
                        android.util.Log.w("CallManager", "NoiseSuppressor init error", e)
                    }
                }

                // Enable hardware Acoustic Echo Cancellation if available
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                            enabled = true
                        }
                        android.util.Log.d("CallManager", "Hardware AcousticEchoCanceler active")
                    } catch (e: Exception) {
                        android.util.Log.w("CallManager", "AcousticEchoCanceler init error", e)
                    }
                }

                // Enable hardware Automatic Gain Control if available
                if (AutomaticGainControl.isAvailable()) {
                    try {
                        gainControl = AutomaticGainControl.create(audioSessionId)?.apply {
                            enabled = true
                        }
                        android.util.Log.d("CallManager", "Hardware AutomaticGainControl active")
                    } catch (e: Exception) {
                        android.util.Log.w("CallManager", "AutomaticGainControl init error", e)
                    }
                }

                record.startRecording()
                audioRecord = record
                android.util.Log.d("CallManager", "Audio recording started at 16000Hz (30ms chunks)")

                val buffer = ByteArray(chunkBytes)
                var seq = 0

                while (scope.isActive && _callState.value is CallState.Active) {
                    val bytesRead = record.read(buffer, 0, buffer.size)
                    if (bytesRead > 0 && !_isMuted.value) {
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
        stopIncomingRingtone()
        stopRingbackTone()

        durationJob?.cancel()
        durationJob = null
        recordingJob?.cancel()
        recordingJob = null

        try {
            noiseSuppressor?.release()
        } catch (e: Exception) {}
        noiseSuppressor = null

        try {
            echoCanceler?.release()
        } catch (e: Exception) {}
        echoCanceler = null

        try {
            gainControl?.release()
        } catch (e: Exception) {}
        gainControl = null

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
        totalWrittenFrames = 0L
    }

    private fun transitionToActive(contactUsername: String, callId: String) {
        stopIncomingRingtone()
        stopRingbackTone()

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
                    startIncomingRingtone()
                } else {
                    // Current user is busy on another call
                    wsClient.sendFrame(MythWebSocketFrame.CallReject(frame.callId, frame.callerUsername, "BUSY"))
                }
            }

            is MythWebSocketFrame.CallAnswer -> {
                val state = _callState.value
                if (state is CallState.Outgoing && state.callId == frame.callId) {
                    stopRingbackTone()
                    transitionToActive(state.contactUsername, state.callId)
                }
            }

            is MythWebSocketFrame.IceCandidate -> {
                if (frame.sdpMid == "audio" && _callState.value is CallState.Active) {
                    val track = audioTrack ?: return
                    try {
                        val pcm = CryptoUtils.fromBase64(frame.sdpCandidate)
                        track.write(pcm, 0, pcm.size)
                    } catch (e: Exception) {
                        android.util.Log.e("CallManager", "Error playing incoming audio frame", e)
                    }
                }
            }

            is MythWebSocketFrame.CallReject -> {
                stopRingbackTone()
                stopIncomingRingtone()
                endAudioSession()
                _callState.value = CallState.Ended(frame.reason)
                scope.launch {
                    delay(800)
                    _callState.value = CallState.Idle
                }
            }

            is MythWebSocketFrame.CallEnd -> {
                stopRingbackTone()
                stopIncomingRingtone()
                endAudioSession()
                _callState.value = CallState.Ended(frame.reason)
                scope.launch {
                    delay(600)
                    _callState.value = CallState.Idle
                }
            }

            else -> {}
        }
    }
}
