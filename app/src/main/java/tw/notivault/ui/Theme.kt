package tw.notivault.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Cyan = Color(0xFF52DDE1)
val Mint = Color(0xFFAFF3D1)
val Violet = Color(0xFFA8A0FF)
private val Dark = darkColorScheme(primary = Cyan, onPrimary = Color(0xFF00363B), secondary = Violet, tertiary = Mint,
    background = Color(0xFF090F1D), onBackground = Color(0xFFEAF0FC), surface = Color(0xFF121C2D),
    onSurface = Color(0xFFEAF0FC), surfaceVariant = Color(0xFF1C2B40), onSurfaceVariant = Color(0xFF98A9C3), outline = Color(0xFF304058))
private val Light = lightColorScheme(primary = Color(0xFF006D77), onPrimary = Color.White, secondary = Color(0xFF6252A5), tertiary = Color(0xFF26735A),
    background = Color(0xFFF4F7FC), onBackground = Color(0xFF152239), surface = Color.White,
    onSurface = Color(0xFF152239), surfaceVariant = Color(0xFFE7EEF6), onSurfaceVariant = Color(0xFF5B6E87), outline = Color(0xFFC6D2E2))

@Composable fun VaultTheme(mode: String, content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) Dark else Light,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 26.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        ), content = content)
}
