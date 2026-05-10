package com.varun.animusglasses

import android.app.Activity
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

// All API calls route through the Animus backend — no keys in the APK
private const val BASE_URL = "https://animusai.app"

data class ChatMessage(val role: String, val text: String)

data class ObjectPersonality(
    val objectType: String,
    val personalitySummary: String,
    val openingLine: String,
    val voice: String,
    val vocalDirection: String
)

data class AnimusUiState(
    val isConnected: Boolean = false,
    val isStreaming: Boolean = false,
    val isScanning: Boolean = false,
    val isTyping: Boolean = false,
    val isRecording: Boolean = false,
    val chatActive: Boolean = false,
    val personality: ObjectPersonality? = null,
    val messages: List<ChatMessage> = emptyList(),
    val statusText: String = "Waiting for glasses...",
    val lastFrameJpeg: ByteArray? = null,
    val error: String? = null
)

class AnimusViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AnimusUiState())
    val uiState: StateFlow<AnimusUiState> = _uiState.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val deviceSelector = AutoDeviceSelector()

    private var sessionJob: Job? = null
    private var recordingThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var isRecordingAudio = false

    // Chat history in Gemini format for the backend
    private val chatHistory = mutableListOf<Map<String, Any>>()

    fun onPermissionsGranted(activity: Activity) {
        viewModelScope.launch {
            val currentState = Wearables.registrationState.first()
            Log.d("AnimusVM", "Registration state: $currentState")
            when (currentState) {
                is RegistrationState.Registered -> {
                    Log.d("AnimusVM", "Already registered, connecting directly")
                    _uiState.update { it.copy(statusText = "Connecting...") }
                    delay(500)
                    startSession()
                }
                else -> {
                    Log.d("AnimusVM", "Not registered, starting registration")
                    _uiState.update { it.copy(statusText = "Registering with Meta AI...") }
                    Wearables.startRegistration(activity)
                    Wearables.registrationState
                        .filterIsInstance<RegistrationState.Registered>()
                        .first()
                    _uiState.update { it.copy(statusText = "Registered — connecting...") }
                    delay(1000)
                    startSession()
                }
            }
        }
    }

    fun onPermissionDenied() {
        _uiState.update { it.copy(error = "Camera permission denied — cannot stream from glasses") }
    }

    private fun startSession() {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            Log.d("AnimusVM", "Creating session...")
            Wearables.createSession(deviceSelector)
                .onSuccess { session ->
                    Log.d("AnimusVM", "Session created, starting...")
                    session.start()
                    launch {
                        session.state.collect { state ->
                            Log.d("AnimusVM", "Session state: $state")
                            when (state) {
                                DeviceSessionState.STARTED -> {
                                    _uiState.update { it.copy(isConnected = true, statusText = "Connected — starting stream") }
                                    session.addStream(
                                        StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 7)
                                    ).onSuccess { stream ->
                                        stream.start()
                                        launch {
                                            stream.state.collect { streamState ->
                                                Log.d("AnimusVM", "Stream state: $streamState")
                                                when (streamState) {
                                                    StreamSessionState.STREAMING -> {
                                                        _uiState.update { it.copy(isStreaming = true, statusText = "Ready — point at an object and scan") }
                                                    }
                                                    StreamSessionState.STOPPED, StreamSessionState.CLOSED -> {
                                                        _uiState.update { it.copy(isStreaming = false) }
                                                    }
                                                    else -> {}
                                                }
                                            }
                                        }
                                        launch {
                                            stream.videoStream.collect { frame ->
                                                val jpeg = i420BufferToJpeg(frame.buffer, frame.width, frame.height)
                                                if (jpeg != null) {
                                                    _uiState.update { it.copy(lastFrameJpeg = jpeg) }
                                                }
                                            }
                                        }
                                    }.onFailure { error, _ ->
                                        Log.e("AnimusVM", "addStream failed: ${error.description}")
                                        _uiState.update { it.copy(error = "Stream failed: ${error.description}") }
                                    }
                                }
                                DeviceSessionState.STOPPED -> {
                                    _uiState.update { it.copy(isConnected = false, isStreaming = false, statusText = "Glasses disconnected") }
                                }
                                else -> {}
                            }
                        }
                    }
                }
                .onFailure { error, _ ->
                    Log.e("AnimusVM", "createSession failed: ${error.description}")
                    _uiState.update { it.copy(statusText = "Retrying in 3s... (${error.description})") }
                    delay(3000)
                    startSession()
                }
        }
    }

    private fun i420BufferToJpeg(buffer: ByteBuffer, width: Int, height: Int): ByteArray? {
        return try {
            val bitmap = i420ToBitmap(buffer, width, height) ?: return null
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.w("AnimusVM", "Frame conversion failed: ${e.message}")
            null
        }
    }

    private fun i420ToBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return null
        val frameSize = width * height
        val expectedSize = frameSize + (frameSize shr 1)
        if (buffer.remaining() < expectedSize) return null

        val yuvBytes = ByteArray(expectedSize)
        val originalPos = buffer.position()
        buffer.get(yuvBytes, 0, expectedSize)
        buffer.position(originalPos)

        val pixels = IntArray(frameSize)
        val halfWidth = width shr 1
        val uOffset = frameSize
        val vOffset = uOffset + (frameSize shr 2)

        var pixelIndex = 0
        for (row in 0 until height) {
            val uvRowOffset = (row shr 1) * halfWidth
            for (col in 0 until width) {
                val uvIndex = uvRowOffset + (col shr 1)
                val y = (yuvBytes[pixelIndex].toInt() and 0xFF) - 16
                val u = (yuvBytes[uOffset + uvIndex].toInt() and 0xFF) - 128
                val v = (yuvBytes[vOffset + uvIndex].toInt() and 0xFF) - 128
                val yScaled = (y * 1192) shr 10
                val r = (yScaled + ((1836 * v) shr 10)).coerceIn(0, 255)
                val g = (yScaled - ((218 * u + 546 * v) shr 10)).coerceIn(0, 255)
                val b = (yScaled + ((2163 * u) shr 10)).coerceIn(0, 255)
                pixels[pixelIndex] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                pixelIndex++
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    fun scan() {
        val jpeg = _uiState.value.lastFrameJpeg ?: run {
            Log.e("AnimusVM", "scan() called but lastFrameJpeg is null")
            return
        }
        Log.d("AnimusVM", "Scanning, JPEG size: ${jpeg.size} bytes")
        _uiState.update { it.copy(isScanning = true, statusText = "Analyzing...") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val personality = identifyObject(jpeg)
                if (personality != null) {
                    chatHistory.clear()
                    _uiState.update {
                        it.copy(
                            isScanning = false,
                            chatActive = true,
                            personality = personality,
                            messages = listOf(ChatMessage("assistant", personality.openingLine)),
                            statusText = "Link established"
                        )
                    }
                    speakText(personality.openingLine, personality.voice, personality.vocalDirection)
                } else {
                    _uiState.update { it.copy(isScanning = false, statusText = "Could not identify — try again") }
                }
            } catch (e: Exception) {
                Log.e("AnimusVM", "Scan exception: ${e.message}", e)
                _uiState.update { it.copy(isScanning = false, error = "Scan failed: ${e.message}") }
            }
        }
    }

    // POST /api/gemini action=vision
    private fun identifyObject(jpegBytes: ByteArray): ObjectPersonality? {
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val body = JsonObject().apply {
            addProperty("action", "vision")
            add("payload", gson.toJsonTree(mapOf(
                "image" to base64Image,
                "mimeType" to "image/jpeg"
            )))
        }

        val request = Request.Builder()
            .url("$BASE_URL/api/gemini")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = http.newCall(request).execute()
        val responseBody = response.body?.string() ?: return null
        Log.d("AnimusVM", "Vision response (${response.code}): ${responseBody.take(300)}")
        if (!response.isSuccessful) return null

        return try {
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            ObjectPersonality(
                objectType = json.get("object_type")?.asString ?: return null,
                personalitySummary = json.get("personality_summary")?.asString ?: "",
                openingLine = json.get("opening_line")?.asString ?: "",
                voice = json.get("voice")?.asString ?: "diana",
                vocalDirection = json.get("vocal_direction")?.asString ?: "calm"
            )
        } catch (e: Exception) {
            Log.e("AnimusVM", "Failed to parse vision response: ${e.message}")
            null
        }
    }

    fun sendMessage(text: String) {
        val personality = _uiState.value.personality ?: return
        _uiState.update { it.copy(isTyping = true, messages = it.messages + ChatMessage("user", text)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Build history in Gemini format for the backend
                chatHistory.add(mapOf("role" to "user", "parts" to listOf(mapOf("text" to text))))

                val body = JsonObject().apply {
                    addProperty("action", "chat")
                    add("payload", gson.toJsonTree(mapOf(
                        "message" to text,
                        "history" to chatHistory.dropLast(1), // backend adds latest message itself
                        "objectType" to personality.objectType,
                        "personality" to personality.personalitySummary,
                        "language" to "english"
                    )))
                }

                val request = Request.Builder()
                    .url("$BASE_URL/api/gemini")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = http.newCall(request).execute()
                val responseBody = response.body?.string() ?: return@launch
                Log.d("AnimusVM", "Chat response: ${responseBody.take(300)}")

                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val replyText = json.get("text")?.asString ?: "[no response]"

                chatHistory.add(mapOf("role" to "model", "parts" to listOf(mapOf("text" to replyText))))
                _uiState.update { it.copy(isTyping = false, messages = it.messages + ChatMessage("assistant", replyText)) }
                speakText(replyText, personality.voice, personality.vocalDirection)

            } catch (e: Exception) {
                Log.e("AnimusVM", "sendMessage exception: ${e.message}", e)
                _uiState.update { it.copy(isTyping = false, error = "Message failed: ${e.message}") }
            }
        }
    }

    // POST /api/speak — returns WAV binary
    private fun speakText(text: String, voice: String, vocalDirection: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("text", text)
                    addProperty("voice", voice)
                    addProperty("vocal_direction", vocalDirection)
                }

                val request = Request.Builder()
                    .url("$BASE_URL/api/speak")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = http.newCall(request).execute()
                Log.d("AnimusVM", "TTS response: ${response.code}")
                if (response.isSuccessful) {
                    val audioBytes = response.body?.bytes() ?: return@launch
                    playAudio(audioBytes)
                }
            } catch (e: Exception) {
                Log.w("AnimusVM", "TTS failed: ${e.message}")
            }
        }
    }

    private fun playAudio(wavBytes: ByteArray) {
        try {
            val sampleRate = 24000
            val bufferSize = android.media.AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val audioTrack = android.media.AudioTrack(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                bufferSize,
                android.media.AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            audioTrack.play()
            val pcmData = wavBytes.drop(44).toByteArray()
            audioTrack.write(pcmData, 0, pcmData.size)
            audioTrack.stop()
            audioTrack.release()
        } catch (e: Exception) {
            Log.w("AnimusVM", "Audio playback failed: ${e.message}")
        }
    }

    fun startRecording(activity: Activity) {
        if (isRecordingAudio) return
        isRecordingAudio = true
        _uiState.update { it.copy(isRecording = true) }
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        val audioBuffer = mutableListOf<Byte>()
        audioRecord?.startRecording()
        recordingThread = Thread {
            val buffer = ByteArray(bufferSize)
            while (isRecordingAudio) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (read > 0) audioBuffer.addAll(buffer.take(read))
            }
            viewModelScope.launch(Dispatchers.IO) {
                val transcript = transcribeAudio(audioBuffer.toByteArray(), sampleRate)
                if (!transcript.isNullOrBlank()) sendMessage(transcript.trim())
            }
        }
        recordingThread?.start()
    }

    fun stopRecording() {
        isRecordingAudio = false
        _uiState.update { it.copy(isRecording = false) }
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    // POST /api/transcribe — multipart audio
    private fun transcribeAudio(pcmBytes: ByteArray, sampleRate: Int): String? {
        val wavBytes = pcmToWav(pcmBytes, sampleRate)
        val requestBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wavBytes.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$BASE_URL/api/transcribe")
            .post(requestBody)
            .build()
        val response = http.newCall(request).execute()
        val body = response.body?.string() ?: return null
        return gson.fromJson(body, JsonObject::class.java).get("text")?.asString
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val wav = ByteArray(pcm.size + 44)
        fun wi(o: Int, v: Int) { wav[o]=(v and 0xff).toByte(); wav[o+1]=((v shr 8) and 0xff).toByte(); wav[o+2]=((v shr 16) and 0xff).toByte(); wav[o+3]=((v shr 24) and 0xff).toByte() }
        fun ws(o: Int, v: Int) { wav[o]=(v and 0xff).toByte(); wav[o+1]=((v shr 8) and 0xff).toByte() }
        wav[0]='R'.code.toByte(); wav[1]='I'.code.toByte(); wav[2]='F'.code.toByte(); wav[3]='F'.code.toByte()
        wi(4, pcm.size + 36)
        wav[8]='W'.code.toByte(); wav[9]='A'.code.toByte(); wav[10]='V'.code.toByte(); wav[11]='E'.code.toByte()
        wav[12]='f'.code.toByte(); wav[13]='m'.code.toByte(); wav[14]='t'.code.toByte(); wav[15]=' '.code.toByte()
        wi(16, 16); ws(20, 1); ws(22, 1); wi(24, sampleRate); wi(28, sampleRate * 2); ws(32, 2); ws(34, 16)
        wav[36]='d'.code.toByte(); wav[37]='a'.code.toByte(); wav[38]='t'.code.toByte(); wav[39]='a'.code.toByte()
        wi(40, pcm.size); System.arraycopy(pcm, 0, wav, 44, pcm.size)
        return wav
    }

    fun terminateChat() {
        _uiState.update { it.copy(chatActive = false, personality = null, messages = emptyList(), statusText = "Ready — point at an object and scan") }
        chatHistory.clear()
    }

    override fun onCleared() {
        super.onCleared()
        stopRecording()
        sessionJob?.cancel()
    }
}
