package com.jimmy.expiryreminder.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jimmy.expiryreminder.R

/**
 * 应用内离线语音弹窗：内置 Vosk 中文模型，AudioRecord 采集、本机识别，
 * 不保存录音、不上传音频。系统识别器可用时才显示「系统语音」兜底。
 */
@Composable
fun VoiceInputDialog(
    prompt: String,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    var partial by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("正在准备离线模型…") }
    var failed by remember { mutableStateOf(false) }
    val engine = remember { VoskEngine(context) }
    val systemAvailable = remember { VoiceInput.canUseSystem(context) }

    val systemFallback = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            VoiceInput.textFrom(result.data)?.let(onResult)
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        engine.start(
            onStatus = { status = it },
            onPartial = { partial = it },
            onFinal = { text -> onResult(text) },
            onError = { message ->
                failed = true
                status = message
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose { engine.destroy() }
    }

    Dialog(
        onDismissRequest = {
            engine.destroy()
            onDismiss()
        },
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text("语音输入", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    partial.ifBlank { status },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "内置 Vosk 中文模型 · 本机离线 · 音频不上传",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = {
                        engine.destroy()
                        onDismiss()
                    }) { Text("取消") }
                    if (systemAvailable) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            engine.destroy()
                            systemFallback.launch(VoiceInput.systemIntent(prompt))
                        }) { Text("系统语音") }
                    }
                }
            }
        }
    }
}
