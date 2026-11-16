package com.jimmy.expiryreminder.ui

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Recognizer

/**
 * 本机 Vosk 流式识别：AudioRecord 采 16k 单声道 PCM，直接喂给 Vosk，
 * 不经过任何网络；模型由 [VoiceInput.ensureModel] 从 assets 解压并缓存。
 */
class VoskEngine(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var job: Job? = null

    @Suppress("MissingPermission")
    fun start(
        onStatus: (String) -> Unit,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (job != null) return
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                post { onStatus("正在准备离线模型…") }
                val model = VoiceInput.ensureModel(context)
                val rec = Recognizer(model, SAMPLE_RATE)
                recognizer = rec

                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE.toInt(),
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                val audio = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE.toInt(),
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, 8192),
                )
                record = audio
                if (audio.state != AudioRecord.STATE_INITIALIZED) {
                    post { onError("麦克风初始化失败，请检查录音权限") }
                    return@launch
                }
                audio.startRecording()
                running = true
                post { onStatus("正在聆听，请说话…") }

                val buffer = ByteArray(4096)
                while (running) {
                    val read = audio.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    if (rec.acceptWaveForm(buffer, read)) {
                        val text = runCatching {
                            JSONObject(rec.result).optString("text").trim()
                        }.getOrDefault("")
                        if (text.isNotEmpty()) {
                            post { onFinal(text) }
                            break
                        }
                    } else {
                        val partial = runCatching {
                            JSONObject(rec.partialResult).optString("partial").trim()
                        }.getOrDefault("")
                        if (partial.isNotEmpty()) post { onPartial(partial) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post { onError("离线识别失败：${e.message ?: "未知错误"}") }
            } finally {
                release()
            }
        }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
    }

    fun destroy() {
        running = false
        runCatching { record?.stop() }
        job?.cancel()
        job = null
        release()
    }

    private fun release() {
        runCatching { record?.release() }
        record = null
        runCatching { recognizer?.close() }
        recognizer = null
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private companion object {
        const val SAMPLE_RATE = 16000.0f
    }
}
