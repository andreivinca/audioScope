package io.github.andreivinca.audioscope.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ScopeBg = Color(0xFF101418)
val ScopeSurface = Color(0xFF1A2029)
val ScopeAccent = Color(0xFF33D17A)
val ScopeAccent2 = Color(0xFF4DA3FF)
val ScopeGrid = Color(0xFF2A323D)
val ScopeText = Color(0xFFE6EBF0)
val ScopeMuted = Color(0xFF8A95A3)

private val DarkColors = darkColorScheme(
    primary = ScopeAccent,
    secondary = ScopeAccent2,
    background = ScopeBg,
    surface = ScopeSurface,
    onPrimary = Color.Black,
    onBackground = ScopeText,
    onSurface = ScopeText,
)

@Composable
fun AudioScopeTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme() // always dark; kept for clarity
    MaterialTheme(colorScheme = DarkColors, content = content)
}
