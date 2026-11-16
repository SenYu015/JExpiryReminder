package com.jimmy.expiryreminder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置项的「?」说明：标题复用条目名，正文写清这个开关/按钮到底做了什么。
 *
 * 文案必须与实现保持一致：改行为时同步改这里，别让说明停留在旧逻辑上。
 */
data class SettingHelp(val title: String, val body: String)

object SettingsHelp {

    val Notify = SettingHelp(
        title = "启用到期通知",
        body = "到期提醒的总开关。\n\n" +
            "· 关闭：立即取消全部已排闹钟，不再推送任何提醒。\n" +
            "· 开启：按当前物品与「推送时刻」重排全部提醒。\n" +
            "· 只有未归档、到期日有效且尚未过期的物品会进排程；每件物品按它在编辑页勾选的「提前提醒天数」逐档提醒，勾了 30/7/1 天就在三个节点各推一条。\n" +
            "· 系统通知权限未授予时，即使这里开着也不会排任何提醒。",
    )

    val NotifyTime = SettingHelp(
        title = "推送时刻",
        body = "所有提醒统一在这一个时刻推送，而不是到期日当天任意时间点。\n\n" +
            "· 触发时间 = (到期日 − 提前天数) 当天的这个时刻。例如到期 6 月 10 日、勾了提前 7 天、时刻 09:00 → 6 月 3 日 09:00 推送。\n" +
            "· 改完立即整包重排，正在等待的闹钟会按新时刻重建。\n" +
            "· 同一天的多条提醒会各自弹一条通知，可能同时堆在通知栏。",
    )

    val AlarmClock = SettingHelp(
        title = "闹钟式提醒",
        body = "决定提醒用哪种系统通道排程。\n\n" +
            "· 开启：走系统的「闹钟」通道（setAlarmClock），抗 Doze 与省电清理最稳；代价是状态栏常驻一个闹钟图标，显示下一次提醒时间。\n" +
            "· 关闭：退化为普通精确闹钟，深度睡眠下可能被系统推迟几分钟到几小时。\n" +
            "· 前提是「精确闹钟权限」已授予；未授予时无论本开关如何，都只能排非精确闹钟。",
    )

    val ExactAlarm = SettingHelp(
        title = "精确闹钟权限",
        body = "本应用不通过应用商店分发，清单里直接声明了精确闹钟权限，安装即授予，正常情况下无需手动操作。\n\n" +
            "· 已授予：提醒可以准点触发。\n" +
            "· 未授予：系统会推迟甚至丢弃提醒，只能排非精确闹钟。\n" +
            "· 部分 ROM 允许用户在系统设置里撤销该权限，撤销时系统会清空全部精确闹钟；重新授权后应用会自动重排。",
    )

    val Battery = SettingHelp(
        title = "忽略电池优化",
        body = "把应用加入系统的电池优化白名单，避免省电策略在后台清掉进程和闹钟。\n\n" +
            "· 已忽略：系统不会因省电限制后台运行，提醒最可靠。\n" +
            "· 未忽略：小米、华为、OPPO、vivo 等机型常会限制后台，导致提醒不弹。\n" +
            "· 这里只申请系统标准白名单，部分 ROM 还需要额外打开自启动。",
    )

    val Autostart = SettingHelp(
        title = "自启动 / 后台运行",
        body = "国产 ROM 的自启动与后台清理不在标准权限体系内，应用无法自行申请，只能手动设置。\n\n" +
            "· 点「查看」可看到小米/华为/OPPO/vivo 的具体设置路径。\n" +
            "· 在最近任务里「强行停止」本应用，系统会清掉全部闹钟；重新打开一次应用即可恢复。\n" +
            "· 这类限制没放开时，任何应用都无法保证定时提醒。",
    )

    val Scheduled = SettingHelp(
        title = "已排程提醒",
        body = "当前真正交给系统 AlarmManager 的提醒条数，以及最近一条的触发时间。\n\n" +
            "· 每件物品的每个提前档位各占一条，一次最多排 60 条；每触发一条，3 分钟后会自动向前补排，窗口持续滚动。\n" +
            "· 显示 0 条，说明没有待提醒的条目，或被总开关、通知权限挡住了。\n" +
            "· 数字来自上次排程后保存的快照；改数据、改设置、开机、改时区都会触发重排。",
    )

    val Theme = SettingHelp(
        title = "主题",
        body = "三档配色，立即生效并保存。\n\n" +
            "· 跟随系统：跟着手机的深色模式开关实时切换。\n" +
            "· 浅色 / 深色：忽略系统设置，始终使用选定配色。\n" +
            "· 切换后桌面小组件的配色也会跟着刷新。",
    )

    val StatCards = SettingHelp(
        title = "主页统计卡",
        body = "决定主页顶部显示哪几张统计卡，勾选即生效并保存。\n\n" +
            "· 可选：全部 / 7天内 / 30天内 / 正常 / 已过期。\n" +
            "· 卡片顺序固定为「全部 → 7天内 → 30天内 → 正常 → 已过期」，与勾选先后无关。\n" +
            "· 至少要保留一张，取消最后一张会被忽略。",
    )

    val Transfer = SettingHelp(
        title = "跨设备分享",
        body = "手动的一次性传输，与「自动同步」完全独立、互不影响。\n\n" +
            "· 同一 Wi-Fi 下，发起方生成 4 位配对码，对方比对确认后才开始发送。\n" +
            "· 按「名称 + 到期日」配对，同一件两边都有时以较新的记录为准；只增改，不删除。\n" +
            "· 离开该页面即关闭端口、配对码作废，传完就断，不常驻后台。",
    )

    val AutoSync = SettingHelp(
        title = "自动同步",
        body = "局域网自动同步的总开关。开启后应用常驻后台：用 mDNS 在同一 Wi-Fi 下广播自己、发现同伴，约每 20 秒合并一轮。\n\n" +
            "· 交换的是增量记录（按修改标记筛选）加上缺失的图片；两边都没改动时只做一次空握手，不写库、不弹通知。\n" +
            "· 同一条记录以修改时间较新的一方为准；两台各自独立录入的相似物品不会自动覆盖，会进「待合并冲突」等你逐条决定。\n" +
            "· 全程只增改、不删除本地物品。关闭后数据只保存在本机。",
    )

    val AutoSyncOnBoot = SettingHelp(
        title = "开机自动同步",
        body = "自动同步的后台兜底排程开关，依赖上面的「自动同步」总开关。\n\n" +
            "· 自动同步开启后，应用会排一个 30 分钟一次的闹钟：进程被系统回收时，由它把应用拉起来补一轮同步。\n" +
            "· 开启（默认）：开机、应用升级、修改系统时间或时区后，都会自动把这条排程恢复回来。\n" +
            "· 关闭：立刻取消这条兜底闹钟，之后也不再恢复；只有应用正在运行时才会同步。\n" +
            "· 自动同步总开关关闭时，本项不起作用，也不会有任何网络活动。",
    )

    val SyncNow = SettingHelp(
        title = "立即同步",
        body = "跳过等待，马上与已发现的所有设备交换一轮数据。\n\n" +
            "· 仅在「自动同步」开启时可用，且两台设备都要开启才能互相发现。\n" +
            "· 进程刚被拉起时 mDNS 还没解析完，会先等约 3 秒再握手。\n" +
            "· 结果会提示「已与 N 台设备同步」或具体失败原因（未发现设备、连不上等）。",
    )

    val Conflicts = SettingHelp(
        title = "待合并冲突",
        body = "自动同步遇到「名称相似或完全一致、但两台设备各自独立录入」的物品时，先放到这里，绝不悄悄覆盖。\n\n" +
            "· 每条可单独决定保留哪一边，或按你的选择合并。\n" +
            "· 冲突判定只看物品记录本身，不比较图片文件。\n" +
            "· 处理完列表会自动清空；同一物品的同一冲突不会重复堆积。",
    )

    val Export = SettingHelp(
        title = "导出备份",
        body = "把 data.json 与全部图片打包成一个 .zip，通过系统的「保存到…」选择存放位置。\n\n" +
            "· 包含：物品、分类、空间等数据 + 全部图片文件。\n" +
            "· 不包含：主题、通知时刻等应用设置（这些在导入后保持本机原有配置）。\n" +
            "· 备份是明文 JSON + 原始图片，自行保管时注意隐私。",
    )

    val Import = SettingHelp(
        title = "导入备份",
        body = "从本应用导出的 .zip 恢复数据，选完文件后会问你要「合并」还是「覆盖」。\n\n" +
            "· 合并：相同 id 的记录被备份覆盖，其余保留，适合多设备汇总。\n" +
            "· 覆盖：先清空当前数据再整包写入，只留备份里的内容。\n" +
            "· 只识别本应用导出的备份；文件损坏或格式不对会提示原因，且不会改动现有数据。",
    )

    val ClearData = SettingHelp(
        title = "清空全部数据",
        body = "删除所有物品及其图片，不可恢复。\n\n" +
            "· 会同时取消全部已排提醒。\n" +
            "· 分类、空间与应用设置不会被重置。\n" +
            "· 建议先「导出备份」再操作。",
    )
}

/**
 * ListItem 的标题行：标题 + 右侧「?」。
 *
 * 「?」用 24dp 触控区包住 17dp 的圆形徽标，行高与原文字行高一致，不会把列表撑高。
 */
@Composable
fun HelpTitle(
    help: SettingHelp,
    modifier: Modifier = Modifier,
    onShow: (SettingHelp) -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(help.title, style = MaterialTheme.typography.bodyLarge)
        Box(
            Modifier
                .padding(start = 4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .clickable { onShow(help) }
                .semantics { contentDescription = "查看「${help.title}」的说明" },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "?",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 点「?」后的详细说明弹窗；正文较长时可滚动。 */
@Composable
fun SettingHelpDialog(help: SettingHelp, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(help.title) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(help.body, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}
