package io.github.hatake716.sekaishiearth.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF1F4C8F),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF3E2600),
    secondaryContainer = Color(0xFF5A3D00),
    onSecondaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFF0B0F1A),
    onBackground = Color(0xFFE3E6F0),
    surface = Color(0xFF131826),
    onSurface = Color(0xFFE3E6F0),
    surfaceVariant = Color(0xFF232A3B),
    onSurfaceVariant = Color(0xFFC3C8D6),
    outline = Color(0xFF6F7689),
    surfaceContainer = Color(0xFF1A2030),
    surfaceContainerHigh = Color(0xFF212838),
    surfaceContainerHighest = Color(0xFF283042),
)

@Composable
fun SekaishiEarthTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
