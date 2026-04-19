package ch.checkbit.tonematrix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ToneMatrixColorScheme = darkColorScheme(
    background = Color.Black,
    surface = Color.Black,
    onBackground = Color(0xFFDDDDDD),
    onSurface = Color(0xFF777777),
    primary = Color.White,
    onPrimary = Color.Black,
)

@Composable
fun ToneMatrixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ToneMatrixColorScheme,
        content = content,
    )
}
