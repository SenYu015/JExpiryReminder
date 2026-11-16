package com.jimmy.expiryreminder.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jimmy.expiryreminder.core.isHexColor

/**
 * ColorOS 17 流体设计的近似落地：更大的圆角层级 + 浅色底，
 * 让卡片像「浮」在纸面上，而不是硬边贴底。
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 以蓝色为主色，并补全中性色层级：
 * 背景中性浅灰（#F8F8F8）、卡片纯白、描边中性灰，让卡片内容从背景里「浮」出来。
 *
 * 注意：底色是中性灰，中性色层级（surfaceVariant / surfaceContainer* / outline）
 * 也必须用中性灰。若留蓝色调（如 #F2F6FB），在灰底上会被对比成一整片发蓝的脏色。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F80ED),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E9FF),
    onPrimaryContainer = Color(0xFF0A3A75),
    secondary = Color(0xFF67C23A),
    onSecondary = Color.White,
    tertiary = Color(0xFFE6A23C),
    onTertiary = Color(0xFF3D2B00),
    error = Color(0xFFF56C6C),
    onError = Color.White,
    // 中性浅灰底（#F8F8F8）：卡片纯白浮在上面，白卡靠描边 + 轻阴影与底色区分
    background = Color(0xFFF8F8F8),
    onBackground = Color(0xFF1F2D3D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2D3D),
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF6B7280),
    // 灰底页面下卡片靠描边区分，描边用中性灰保证与底色同色相
    outline = Color(0xFFE2E4E7),
    outlineVariant = Color(0xFFEDEEF0),
    // surfaceContainer 系列：下拉菜单/弹层用，必须显式给中性灰，
    // 否则回退到 Material 基线的紫调（下拉框发粉的原因）
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F8),
    surfaceContainer = Color(0xFFF1F2F3),
    surfaceContainerHigh = Color(0xFFEAEBED),
    surfaceContainerHighest = Color(0xFFE4E5E7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF66B1FF),
    onPrimary = Color(0xFF0A2A4A),
    primaryContainer = Color(0xFF174A7C),
    onPrimaryContainer = Color(0xFFCCE5FF),
    secondary = Color(0xFF85CE61),
    onSecondary = Color(0xFF0E2A08),
    tertiary = Color(0xFFEBB563),
    onTertiary = Color(0xFF3D2B00),
    error = Color(0xFFF78989),
    onError = Color(0xFF3D0B0B),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE3E6EA),
    surface = Color(0xFF1A1D21),
    onSurface = Color(0xFFE3E6EA),
    surfaceVariant = Color(0xFF23272E),
    onSurfaceVariant = Color(0xFFA2A9B5),
    outline = Color(0xFF3A414B),
    outlineVariant = Color(0xFF2A3038),
    surfaceContainerLowest = Color(0xFF0E1013),
    surfaceContainerLow = Color(0xFF17191D),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF24272C),
    surfaceContainerHighest = Color(0xFF2B2F35),
)

@Composable
fun AppTheme(themeMode: String, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}

/** `#RRGGBB` → Compose Color；非法返回灰色。 */
fun hexColor(value: String?): Color {
    if (value == null || !isHexColor(value)) return Color(0xFF909399)
    val hex = value.substring(1)
    return Color(android.graphics.Color.parseColor(value))
}
