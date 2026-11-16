package com.jimmy.expiryreminder.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 离线语音输入。
 *
 * ColorOS 17 没有向第三方开放 `android.speech.RecognitionService`（真机
 * `cmd package query-services -a android.speech.RecognitionService` 返回空），
 * 因此主路径改为**内置 Vosk 中文小模型**：模型随 APK 打包，首次使用解压到
 * 应用内部存储，识别全程本机完成、不联网、不上传音频。
 *
 * 系统识别器只作为可选兜底（部分 ROM 提供时才有），不再作为启用条件。
 */
object VoiceInput {

    private const val ASSET_MODEL = "model-cn"
    private const val TARGET_DIR = "vosk-cn"

    @Volatile
    private var cachedModel: Model? = null

    /** 模型随包提供，入口始终可用；真正的错误（麦克风/模型）在弹窗里提示。 */
    fun canUse(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = true

    fun canUseSystem(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    /** 首次调用会把 assets 里的模型解压到 files/vosk-cn，之后直接复用。 */
    suspend fun ensureModel(context: Context): Model = withContext(Dispatchers.IO) {
        cachedModel?.let { return@withContext it }
        val target = File(context.filesDir, TARGET_DIR)
        val ready = File(target, "conf/model.conf").exists()
        val loaded = if (ready) {
            Model(target.absolutePath)
        } else {
            suspendCancellableCoroutine { cont ->
                StorageService.unpack(
                    context,
                    ASSET_MODEL,
                    TARGET_DIR,
                    { model -> cont.resume(model) },
                    { error -> cont.resumeWithException(error) },
                )
            }
        }
        cachedModel = loaded
        loaded
    }

    /** 系统识别界面（兜底，强制离线偏好；没有系统服务时不可用）。 */
    fun systemIntent(prompt: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toString())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }

    fun textFrom(data: Intent?): String? =
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
