package es.personal.avisosairef.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF990033),
    secondary = Color(0xFF546E7A),
    tertiary = Color(0xFF2E7D32)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C8),
    secondary = Color(0xFFB0BEC5),
    tertiary = Color(0xFFA5D6A7)
)

@Composable
fun AvisosAirefTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
