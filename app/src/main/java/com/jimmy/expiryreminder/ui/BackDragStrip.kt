package com.jimmy.expiryreminder.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 左边缘跟手返回手势条（参考酷安的做法）。
 *
 * ColorOS 17 不分发预测性返回进度（onBackProgressed 恒为 0，左右边缘皆然），
 * 酷安是把左边缘申请了系统手势排除后自己处理触摸。这里同样：
 * 铺一条 32dp 左边缘手势条并调用 systemGestureExclusion() 从系统手势里排除
 * （系统对排除矩形有约 200dp 高度配额，超出部分仍走系统返回 → 回落到
 * 原有的按钮同款转场），拖拽进度完全由自己的触摸事件驱动。
 *
 * 未激活时不消费事件，边缘上的正常点击不受影响；激活（水平位移超过
 * touchSlop 且大于垂直位移）后消费事件，并按 [screenWidthPx] 折算成
 * 0..1 的进度回调 [onDrag]；松手时按位移比例与横向速度回调
 * [onFinish](commit)。
 */
@Composable
fun BackDragStrip(
    enabled: Boolean,
    screenWidthPx: Float,
    onDrag: (progress: Float) -> Unit,
    onFinish: (commit: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxHeight()
            .width(32.dp)
            .systemGestureExclusion()
            .pointerInput(enabled, screenWidthPx) {
                if (!enabled || screenWidthPx <= 0f) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var active = false
                    var totalDx = 0f
                    var totalDy = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                            ?: break
                        if (!change.pressed) {
                            if (active) {
                                // 消费抬起事件，避免边缘拖拽结束时误触发下方控件的点击
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val velocity = tracker.calculateVelocity()
                                val progress = (totalDx / screenWidthPx).coerceIn(0f, 1f)
                                onFinish(progress > 0.33f || velocity.x > 1000f)
                            }
                            break
                        }
                        val delta = change.positionChange()
                        totalDx += delta.x
                        totalDy += delta.y
                        if (!active) {
                            val slop = viewConfiguration.touchSlop
                            if (abs(totalDx) > slop && abs(totalDx) > abs(totalDy)) active = true
                        }
                        if (active) {
                            onDrag((totalDx / screenWidthPx).coerceIn(0f, 1f))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        tracker.addPosition(change.uptimeMillis, change.position)
                    }
                }
            },
    )
}
