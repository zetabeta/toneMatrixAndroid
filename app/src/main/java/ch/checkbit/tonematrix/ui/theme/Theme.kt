package ch.checkbit.tonematrix.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Color(0xFFFF9500),
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFDDDDDD),
    surface = Color.Black,
    onSurface = Color(0xFF777777),
    surfaceVariant = Color(0xFF333333),
    onSurfaceVariant = Color(0xFFAAAAAA),
    outline = Color(0xFF808080),
)

private val LightColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    secondary = Color(0xFFE68600), // Slightly darker orange for better contrast on white
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF222222),
    surface = Color.White,
    onSurface = Color(0xFF666666),
    surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFF444444),
    outline = Color(0xFFBBBBBB),
)

@Composable
fun ToneMatrixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
