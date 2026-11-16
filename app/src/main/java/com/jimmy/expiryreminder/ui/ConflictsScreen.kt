package com.jimmy.expiryreminder.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.core.AutoSyncMerge
import com.jimmy.expiryreminder.core.ConflictStore
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.PendingConflict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 用户可选的四种处理方式。 */
enum class ConflictAction(val label: String) {
    KEEP_LOCAL("保留本地"),
    TAKE_INCOMING("采用对方"),
    MERGE("智能合并"),
    KEEP_BOTH("各自保留"),
}

/**
 * 自动同步发现的疑似重复物品：不自动覆盖，由用户逐条决定。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConflictsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val conflicts by ConflictStore.conflicts.collectAsStateWithLifecycle()

    // 进程恢复时重新读一次文件，保证设置页角标与本页一致
    LaunchedEffect(Unit) { ConflictStore.load(context) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("待合并冲突（${conflicts.size}）") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (conflicts.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无待合并冲突", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "自动同步遇到名称相似或完全一致的物品时，会先放在这里，不会悄悄覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(conflicts, key = { it.key }) { conflict ->
                    ConflictCard(
                        conflict = conflict,
                        onResolve = { action ->
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { resolveConflict(context, conflict, action) }
                                }
                                snackbar.showSnackbar(
                                    if (result.isSuccess) "已${action.label}"
                                    else "处理失败：${result.exceptionOrNull()?.message}",
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

private suspend fun resolveConflict(context: Context, conflict: PendingConflict, action: ConflictAction) {
    val store = ExpiryStore.get()
    when (action) {
        ConflictAction.KEEP_LOCAL -> Unit
        ConflictAction.TAKE_INCOMING -> store.updateItem(
            context,
            conflict.local.id,
            // 图片不随自动同步传输，覆盖时保留本机图片
            conflict.incoming.copy(
                id = conflict.local.id,
                createdAt = conflict.local.createdAt,
                images = conflict.local.images,
                notifiedKeys = emptyList(),
            ),
        )
        ConflictAction.MERGE -> store.updateItem(
            context,
            conflict.local.id,
            AutoSyncMerge.mergeFields(conflict.local, conflict.incoming),
        )
        ConflictAction.KEEP_BOTH -> store.createItem(
            context,
            conflict.incoming.copy(images = emptyList(), notifiedKeys = emptyList(), archived = false),
        )
    }
    ConflictStore.remove(context, conflict.key)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictCard(conflict: PendingConflict, onResolve: (ConflictAction) -> Unit) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                conflict.reason,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "来自「${conflict.from}」 · 相似度 ${(conflict.score * 100).toInt()}% · ${conflict.detectedAt.take(16).replace('T', ' ')}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ItemColumn("本机", conflict.local, Modifier.weight(1f))
                ItemColumn("对方", conflict.incoming, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ConflictAction.entries.forEach { action ->
                    OutlinedButton(
                        onClick = { onResolve(action) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = if (action == ConflictAction.MERGE) {
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                    ) {
                        Text(action.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "智能合并：空字段由对方补齐、到期日取较早、备注合并、图片保留本机。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ItemColumn(title: String, item: ExpiryItem, modifier: Modifier = Modifier) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            FieldLine("名称", item.name)
            FieldLine("到期", item.expireAt.ifBlank { "未填" })
            FieldLine("数量", "×${item.quantity}")
            FieldLine("分类", item.category)
            if (item.note.isNotBlank()) FieldLine("备注", item.note)
        }
    }
}

@Composable
private fun FieldLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
