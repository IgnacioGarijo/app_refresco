package es.personal.avisosairef.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF063347),
    secondary = Color(0xFFB3BEC6),
    tertiary = Color(0xFF2F6F73),
    surface = Color(0xFFFAFCFD),
    background = Color(0xFFF6F9FB)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CCFE8),
    secondary = Color(0xFFB3BEC6),
    tertiary = Color(0xFF95D0C9),
    surface = Color(0xFF10191D),
    background = Color(0xFF071115)
)

@Composable
fun RefrescoWebTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
