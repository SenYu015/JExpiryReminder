package com.jimmy.expiryreminder.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.BuildConfig
import com.jimmy.expiryreminder.core.AutoSyncEngine
import com.jimmy.expiryreminder.core.Backup
import com.jimmy.expiryreminder.core.ConflictStore
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SettingsStore
import com.jimmy.expiryreminder.core.StatCard
import com.jimmy.expiryreminder.core.SyncStateStore
import com.jimmy.expiryreminder.widget.ExpiryWidgetBase
import com.jimmy.expiryreminder.notify.ReminderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/** 设置页：主题、通知、备份导出导入、跨设备传输、清空数据。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onTransfer: () -> Unit, onConflicts: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val store = ExpiryStore.get()
    val settings = SettingsStore.get()
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val notifyEnabled by settings.notifyEnabled.collectAsStateWithLifecycle()
    val notifyTime by settings.notifyTime.collectAsStateWithLifecycle()
    val useAlarmClock by settings.useAlarmClock.collectAsStateWithLifecycle()
    val statCards by settings.statCards.collectAsStateWithLifecycle()
    val autoSyncEnabled by settings.autoSyncEnabled.collectAsStateWithLifecycle()
    val autoSyncOnBoot by settings.autoSyncOnBoot.collectAsStateWithLifecycle()
    val predictiveBackEnabled by settings.predictiveBackEnabled.collectAsStateWithLifecycle()
    val liveUpdateEnabled by settings.liveUpdateEnabled.collectAsStateWithLifecycle()
    val syncStatus by AutoSyncEngine.status.collectAsStateWithLifecycle()
    val pendingConflicts by ConflictStore.conflicts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var showTimePicker by remember { mutableStateOf(false) }
    // 滚轮对话框里的临时选择，点「确定」才写回设置
    var wheelHour by remember { mutableIntStateOf(notifyTime.substringBefore(':').toIntOrNull() ?: 9) }
    var wheelMinute by remember { mutableIntStateOf(notifyTime.substringAfter(':').toIntOrNull() ?: 0) }
    var showStatCards by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showRomHelp by remember { mutableStateOf(false) }
    var showLiveHelp by remember { mutableStateOf(false) }

    // 权限/排程状态每次回到前台都重新读一遍：用户去系统设置改完回来能立刻看到结果
    var reliabilityTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reliabilityTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val exactGranted = remember(reliabilityTick) { ReminderService.canScheduleExact(context) }
    val batteryIgnored = remember(reliabilityTick) {
        ReminderService.isIgnoringBatteryOptimizations(context)
    }
    val scheduledInfo = remember(reliabilityTick, notifyEnabled, notifyTime, useAlarmClock) {
        ReminderService.scheduledInfo(context)
    }
    // 流体云 / 系统实时动态能力：每次回前台重读，用户去系统设置改完能立刻看到结果
    val liveStatus = remember(reliabilityTick, liveUpdateEnabled) {
        ReminderService.liveUpdateStatus(context)
    }
    val liveStatusText = when {
        !liveStatus.supported -> "系统低于 Android 16，不支持实时动态"
        liveStatus.promoted -> "已提升为实时动态 / 状态栏胶囊"
        !liveStatus.canPost -> "系统未允许本应用发布实时动态，点「去开启」到系统设置"
        !liveStatus.active -> "当前没有 7 天内到期物品，或汇总尚未发布"
        liveStatus.promotable == false -> "通知不满足系统提升条件"
        else -> "已请求提升但暂未提升；可点「重发」，或到系统设置检查实时活动开关"
    }
    // 已配对/已同步过的设备数：读本机水位文件，回前台刷新
    val pairedCount = remember(reliabilityTick, autoSyncEnabled) {
        SyncStateStore.peerCount(context)
    }

    // 一级分类折叠状态：默认展开常用两组，其余收起，减少一次看到的信息量
    var expandedGroups by remember { mutableStateOf(setOf("通知与提醒", "外观与交互")) }
    fun toggleGroup(key: String) {
        expandedGroups = if (key in expandedGroups) expandedGroups - key else expandedGroups + key
    }

    // 导出：SAF 建文档
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    Backup.exportTo(context, uri, store)
                    snackbar.showSnackbar("备份已导出")
                } catch (e: Exception) {
                    snackbar.showSnackbar("导出失败：${e.message}")
                }
            }
        }
    }

    // 导入：SAF 选文件
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingImport = uri
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入备份") },
            text = { Text("覆盖：清空当前数据后整包写入。\n合并：相同 id 的记录会被备份覆盖，其余保留。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = uri
                    pendingImport = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            val payload = Backup.parseFrom(context, target)
                            val result = Backup.applyBackup(context, store, payload, Backup.ImportMode.merge)
                            snackbar.showSnackbar("合并完成：备份 ${result.replacedItems} 件，现有 ${result.totalItems} 件")
                        } catch (e: Exception) {
                            snackbar.showSnackbar("导入失败：${e.message}")
                        }
                    }
                }) { Text("合并") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val target = uri
                    pendingImport = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            val payload = Backup.parseFrom(context, target)
                            val result = Backup.applyBackup(context, store, payload, Backup.ImportMode.replace)
                            snackbar.showSnackbar("覆盖完成：共 ${result.totalItems} 件")
                        } catch (e: Exception) {
                            snackbar.showSnackbar("导入失败：${e.message}")
                        }
                    }
                }) { Text("覆盖") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            SettingsGroup(
                title = "通知与提醒",
                summary = "提醒开关、推送时刻与后台可靠性",
                expanded = "通知与提醒" in expandedGroups,
                onToggle = { toggleGroup("通知与提醒") },
            ) {
                SubsectionLabel("提醒开关")


            ListItem(
                headlineContent = { Text("启用到期通知") },
                supportingContent = { Text("关闭后不再推送任何提醒") },
                trailingContent = {
                    Switch(
                        checked = notifyEnabled,
                        onCheckedChange = { enabled ->
                            settings.update(context, notifyEnabled = enabled)
                            ReminderService.syncScheduled(
                                context, store, enabled, notifyTime, useAlarmClock, liveUpdateEnabled,
                            )
                        },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("推送时刻") },
                supportingContent = { Text("每天 $notifyTime 推送到期提醒") },
                trailingContent = {
                    OutlinedButton(onClick = {
                        // 每次打开都从当前设置重新起算，滚轮从现有时刻开始
                        wheelHour = notifyTime.substringBefore(':').toIntOrNull() ?: 9
                        wheelMinute = notifyTime.substringAfter(':').toIntOrNull() ?: 0
                        showTimePicker = true
                    }) { Text(notifyTime) }
                },
            )

            ListItem(
                headlineContent = { Text("流体云提醒") },
                supportingContent = {
                    Text(
                        if (liveUpdateEnabled) "7 天内到期物品合成一条常驻提醒；能否上流体云由系统决定"
                        else "已关闭，只保留逐条到期通知",
                    )
                },
                trailingContent = {
                    Switch(
                        checked = liveUpdateEnabled,
                        onCheckedChange = { enabled ->
                            settings.update(context, liveUpdateEnabled = enabled)
                            scope.launch {
                                ReminderService.syncScheduled(
                                    context, store, notifyEnabled, notifyTime, useAlarmClock, enabled,
                                )
                            }
                        },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("流体云 / 实时动态诊断") },
                supportingContent = { Text(liveStatusText) },
                modifier = Modifier.clickable { showLiveHelp = true },
                trailingContent = {
                    Row {
                        TextButton(onClick = { ReminderService.openLiveUpdateSettings(context) }) {
                            Text("去开启")
                        }
                        TextButton(onClick = {
                            ReminderService.repostLiveSummary(context, store, liveUpdateEnabled)
                            reliabilityTick++
                            scope.launch { snackbar.showSnackbar("已重新发布到期汇总") }
                        }) { Text("重发") }
                    }
                },
            )

                SubsectionLabel("后台可靠性")


            ListItem(
                headlineContent = { Text("闹钟式提醒") },
                supportingContent = {
                    Text("状态栏显示闹钟图标；抗系统省电清理最稳。关闭后提醒可能延后几分钟到几小时")
                },
                trailingContent = {
                    Switch(
                        checked = useAlarmClock,
                        onCheckedChange = { enabled ->
                            settings.update(context, useAlarmClock = enabled)
                            ReminderService.syncScheduled(
                                context, store, notifyEnabled, notifyTime, enabled, liveUpdateEnabled,
                            )
                        },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("精确闹钟权限") },
                supportingContent = {
                    Text(
                        if (exactGranted) "已授予，提醒可以准点触发"
                        else "未授予，只能排非精确闹钟，后台很容易被系统推迟甚至丢掉"
                    )
                },
                trailingContent = {
                    if (exactGranted) {
                        Text(
                            "已授予",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        TextButton(onClick = { openExactAlarmSettings(activity, context) }) {
                            Text("去授权")
                        }
                    }
                },
            )

            ListItem(
                headlineContent = { Text("忽略电池优化") },
                supportingContent = {
                    Text(
                        if (batteryIgnored) "已忽略，系统不会因省电清理掉提醒"
                        else "未忽略，部分机型会限制后台运行，导致提醒不弹"
                    )
                },
                trailingContent = {
                    if (batteryIgnored) {
                        Text(
                            "已忽略",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        TextButton(onClick = { openBatterySettings(activity, context) }) {
                            Text("去设置")
                        }
                    }
                },
            )

            ListItem(
                headlineContent = { Text("自启动 / 后台运行") },
                supportingContent = { Text("小米、华为、OPPO、vivo 等机型还需手动允许") },
                trailingContent = {
                    TextButton(onClick = { showRomHelp = true }) { Text("查看") }
                },
            )

            ListItem(
                headlineContent = { Text("已排程提醒") },
                supportingContent = {
                    Text(
                        if (scheduledInfo.count == 0) {
                            "当前没有待提醒的条目"
                        } else {
                            buildString {
                                append("共 ${scheduledInfo.count} 条")
                                scheduledInfo.nextAt?.let {
                                    append(" · 下一次 ")
                                    append(it.format(NEXT_AT_FORMAT))
                                }
                            }
                        }
                    )
                },
            )

            }

            SettingsGroup(
                title = "外观与交互",
                summary = "浅色 / 深色主题、页面动效与离线语音",
                expanded = "外观与交互" in expandedGroups,
                onToggle = { toggleGroup("外观与交互") },
            ) {
                SubsectionLabel("主题")


            // 横向单选项，三档并排一行，节省纵向空间
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (mode in SettingsStore.ThemeMode.entries) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .selectable(
                                selected = themeMode == mode,
                                onClick = {
                                    settings.update(context, themeMode = mode)
                                    ExpiryWidgetBase.refreshAll(context)
                                },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 2.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = themeMode == mode,
                            onClick = null,
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            modeLabel(mode),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                    }
                }
            }

                SubsectionLabel("页面")

            ListItem(
                headlineContent = { Text("主页统计卡") },
                supportingContent = { Text(statCards.joinToString(" / ") { it.label }) },
                modifier = Modifier.clickable { showStatCards = true },
                trailingContent = {
                    TextButton(onClick = { showStatCards = true }) { Text("选择") }
                },
            )

            ListItem(
                headlineContent = { Text("预测性返回动画") },
                supportingContent = {
                    Text(
                        if (predictiveBackEnabled) "返回时页面跟手缩小，可预览回到桌面"
                        else "已关闭，使用普通返回动画",
                    )
                },
                trailingContent = {
                    Switch(
                        checked = predictiveBackEnabled,
                        onCheckedChange = { enabled ->
                            settings.update(context, predictiveBackEnabled = enabled)
                            scope.launch {
                                snackbar.showSnackbar(if (enabled) "预测性返回已开启" else "预测性返回已关闭")
                            }
                        },
                    )
                },
            )

                SubsectionLabel("语音")

            ListItem(
                headlineContent = { Text("语音识别") },
                supportingContent = {
                    Text(
                        if (VoiceInput.canUseSystem(context)) {
                            "内置 Vosk 中文模型；系统语音也可用（离线优先）"
                        } else {
                            "内置 Vosk 中文模型，本机离线识别，音频不上传"
                        },
                    )
                },
            )

            }

            SettingsGroup(
                title = "跨设备同步",
                summary = "手动分享与同一 Wi-Fi 自动同步",
                expanded = "跨设备同步" in expandedGroups,
                onToggle = { toggleGroup("跨设备同步") },
            ) {
                SubsectionLabel("手动分享")


            ListItem(
                headlineContent = { Text("跨设备分享") },
                supportingContent = { Text("同一 Wi-Fi 下与另一台设备配对，双向传输物品记录") },
                trailingContent = {
                    OutlinedButton(onClick = onTransfer) { Text("打开") }
                },
            )

                SubsectionLabel("自动同步")


            ListItem(
                headlineContent = { Text("自动同步") },
                supportingContent = { Text("开启后同一 Wi-Fi 下的设备自动合并；共同唯一 ID + 修改时间新者覆盖，不再产生人工冲突") },
                trailingContent = {
                    Switch(
                        checked = autoSyncEnabled,
                        onCheckedChange = { enabled ->
                            settings.update(context, autoSyncEnabled = enabled)
                            scope.launch {
                                snackbar.showSnackbar(if (enabled) "自动同步已开启" else "自动同步已关闭")
                            }
                        },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("开机自动同步") },
                supportingContent = { Text("开机后恢复 30 分钟一次的局域网同步排程") },
                trailingContent = {
                    Switch(
                        checked = autoSyncOnBoot,
                        onCheckedChange = { enabled ->
                            settings.update(context, autoSyncOnBoot = enabled)
                            AutoSyncEngine.scheduleIfEnabled(context)
                            scope.launch {
                                snackbar.showSnackbar(if (enabled) "开机同步已开启" else "开机同步已关闭")
                            }
                        },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("同步状态") },
                supportingContent = {
                    Text(
                        if (pairedCount > 0) "$syncStatus · 已配对 $pairedCount 台"
                        else syncStatus,
                    )
                },
            )

            ListItem(
                headlineContent = { Text("立即同步") },
                supportingContent = { Text("马上与已发现的设备交换一次数据") },
                trailingContent = {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { AutoSyncEngine.syncNow(context) }
                                    .getOrElse { "同步失败：${it.message}" }
                            }
                            snackbar.showSnackbar(result)
                        }
                    }) { Text("同步") }
                },
            )

            ListItem(
                headlineContent = { Text("待合并冲突") },
                supportingContent = {
                    Text(
                        if (pendingConflicts.isEmpty()) "暂无需要人工确认的物品"
                        else "${pendingConflicts.size} 件疑似重复物品等待处理",
                    )
                },
                modifier = Modifier.clickable { onConflicts() },
                trailingContent = {
                    TextButton(onClick = onConflicts) { Text("查看") }
                },
            )

            }

            SettingsGroup(
                title = "桌面小组件",
                summary = "到期列表、显示内容与主题跟随",
                expanded = "桌面小组件" in expandedGroups,
                onToggle = { toggleGroup("桌面小组件") },
            ) {
                SubsectionLabel("外观")


            ListItem(
                headlineContent = { Text("跟随应用外观") },
                supportingContent = {
                    Text("浅色 / 深色主题由「外观与交互」统一控制，小组件自动切换卡片与文字颜色")
                },
                trailingContent = {
                    Text(
                        when (themeMode) {
                            SettingsStore.ThemeMode.light -> "浅色"
                            SettingsStore.ThemeMode.dark -> "深色"
                            else -> "跟随系统"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )

            ListItem(
                headlineContent = { Text("显示内容") },
                supportingContent = {
                    Text("分类色条、所在空间、添加日期、剩余天数与到期日期；点条目进编辑页")
                },
            )

            ListItem(
                headlineContent = { Text("刷新桌面卡片") },
                supportingContent = { Text("主题或数据未及时更新时，可手动刷新一次") },
                trailingContent = {
                    TextButton(onClick = {
                        ExpiryWidgetBase.refreshAll(context)
                        scope.launch { snackbar.showSnackbar("桌面小组件已刷新") }
                    }) { Text("刷新") }
                },
            )

            }

            SettingsGroup(
                title = "数据与备份",
                summary = "导出 / 导入备份与数据清理",
                expanded = "数据与备份" in expandedGroups,
                onToggle = { toggleGroup("数据与备份") },
            ) {
                SubsectionLabel("备份")


            ListItem(
                headlineContent = { Text("导出备份") },
                supportingContent = { Text("打包 data.json 与全部图片（.zip）") },
                trailingContent = {
                    OutlinedButton(onClick = { exportLauncher.launch(Backup.backupFileName()) }) {
                        Text("导出")
                    }
                },
            )

            ListItem(
                headlineContent = { Text("导入备份") },
                supportingContent = { Text("支持本应用导出的备份") },
                trailingContent = {
                    OutlinedButton(onClick = {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    }) { Text("导入") }
                },
            )

                SubsectionLabel("数据")


            ListItem(
                headlineContent = { Text("清空全部数据") },
                supportingContent = { Text("删除所有物品与图片，不可恢复") },
                trailingContent = {
                    TextButton(onClick = { showClearConfirm = true }) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                },
            )

            }

            SettingsGroup(
                title = "关于",
                summary = "版本与作者",
                expanded = "关于" in expandedGroups,
                onToggle = { toggleGroup("关于") },
            ) {

            ListItem(
                headlineContent = { Text("到期提醒") },
                supportingContent = { Text("作者：Jimmy & Sunny") },
                trailingContent = {
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            }
        }
    }

    if (showStatCards) {
        // 勾选立即生效并保存；取消最后一张时忽略这次点击，保证主页至少有一张卡
        AlertDialog(
            onDismissRequest = { showStatCards = false },
            title = { Text("主页统计卡") },
            text = {
                Column {
                    for (card in StatCard.entries) {
                        val checked = card in statCards
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    val next = if (checked) statCards - card else statCards + card
                                    if (next.isNotEmpty()) {
                                        settings.update(context, statCards = next)
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        card.status?.let { hexColor(it.color) }
                                            ?: MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(card.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "按此顺序显示在主页顶部，至少保留一张。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatCards = false }) { Text("完成") }
            },
        )
    }

    if (showTimePicker) {
        // 用自绘 Dialog 而不是 AlertDialog：滚轮中心行可以直接敲数字，
        // 数字键盘弹起时需要 imePadding 把整块内容顶上去，AlertDialog 的默认窗口做不到
        Dialog(
            onDismissRequest = { showTimePicker = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                // 交给 Compose 自己处理 insets，否则窗口 resize 和 imePadding 会叠成双倍偏移
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .imePadding(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                        Text(
                            "选择推送时刻",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Text(
                            "滑动选择，或在下方输入框直接输入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        TimeWheelPicker(
                            hour = wheelHour,
                            minute = wheelMinute,
                            onHourChange = { wheelHour = it },
                            onMinuteChange = { wheelMinute = it },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { showTimePicker = false }) { Text("取消") }
                            TextButton(onClick = {
                                // 滚轮里可能正在输入（只敲了一位还没自动提交），
                                // 先让它失焦把草稿交上来，等一帧再读，否则会存到旧值
                                focusManager.clearFocus()
                                scope.launch {
                                    withFrameNanos { }
                                    val time = "%02d:%02d".format(wheelHour, wheelMinute)
                                    settings.update(context, notifyTime = time)
                                    ReminderService.syncScheduled(
                                        context, store, notifyEnabled, time, useAlarmClock, liveUpdateEnabled,
                                    )
                                    showTimePicker = false
                                }
                            }) { Text("确定") }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空全部数据") },
            text = { Text("⚠️ 此操作非常危险，可能导致不可逆的数据丢失！建议先导出备份。确定要继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch {
                        store.clearItems(context)
                        ReminderService.syncScheduled(
                            context, store, notifyEnabled, notifyTime, useAlarmClock, liveUpdateEnabled,
                        )
                        snackbar.showSnackbar("已清空全部物品")
                    }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showLiveHelp) {
        AlertDialog(
            onDismissRequest = { showLiveHelp = false },
            title = { Text("流体云为什么没有出现") },
            text = {
                Text(
                    "普通通知栏到期提醒与「流体云 / Android 实时动态」是两条链路。\n\n" +
                        "本应用按 Android 16+ 规范发布 promoted ongoing 汇总通知。真机排查发现：" +
                        "旧版用 CATEGORY_REMINDER 且默认 PRIVATE 可见性时，系统只记录请求、" +
                        "不给 FLAG_PROMOTED_ONGOING；改为 CATEGORY_STATUS + VISIBILITY_PUBLIC +" +
                        " 非彩色化后，真机已出现 PROMOTED_ONGOING 标记。\n\n" +
                        "若状态仍是「未提升」，先点「去开启」确认系统里的实时活动开关，再点「重发」；" +
                        "系统设置页里「实时活动会以流体云展示，不受通知设置的影响」需保持开启。" +
                        "自定义模板/多端流体云卡片才需要 OPPO 泛在服务框架与企业白名单。",
                )
            },
            confirmButton = {
                TextButton(onClick = { showLiveHelp = false }) { Text("知道了") }
            },
        )
    }

    if (showRomHelp) {
        AlertDialog(
            onDismissRequest = { showRomHelp = false },
            title = { Text("允许后台运行") },
            text = {
                Text(
                    "系统不允许自启动或强制清理后台时，任何应用都无法定时提醒。可按机型检查：\n\n" +
                        "· 小米 / 红米：设置 → 应用设置 → 应用管理 → 到期提醒 → 自启动 + 省电策略「无限制」\n" +
                        "· 华为 / 荣耀：设置 → 应用 → 应用启动管理 → 到期提醒 → 手动管理（全部打开）\n" +
                        "· OPPO / 一加：设置 → 应用 → 自启动管理，耗电管理选「允许后台运行」\n" +
                        "· vivo / iQOO：设置 → 应用与权限 → 自启动，后台耗电管理选「允许后台高耗电」\n\n" +
                        "另外：在最近任务里「强行停止」应用会让系统清掉全部闹钟，" +
                        "重新打开一次应用即可恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showRomHelp = false }) { Text("知道了") }
            },
        )
    }
}

/** 一级分类卡片：标题 + 概要 + 展开/收起，二级设置放在卡片内。 */
@Composable
private fun SettingsGroup(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                    content = content,
                )
            }
        }
    }
}

/** 二级设置小标题：在分组卡片内再切一层，避免所有开关平铺。 */
@Composable
private fun SubsectionLabel(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun modeLabel(mode: SettingsStore.ThemeMode): String = when (mode) {
    SettingsStore.ThemeMode.system -> "跟随系统"
    SettingsStore.ThemeMode.light -> "浅色"
    SettingsStore.ThemeMode.dark -> "深色"
}

/** 「下一次提醒」的展示格式。 */
private val NEXT_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

/** 跳到系统的「闹钟和提醒」特殊权限页。 */
private fun openExactAlarmSettings(activity: Activity?, context: Context) {
    runCatching {
        activity?.startActivity(
            Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    }
}

/** 申请忽略电池优化；部分 ROM 没有直连入口时退回到电池优化列表。 */
private fun openBatterySettings(activity: Activity?, context: Context) {
    val direct = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
    runCatching { activity?.startActivity(direct) }.onFailure {
        runCatching { activity?.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

