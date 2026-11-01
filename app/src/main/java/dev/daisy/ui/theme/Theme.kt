package dev.daisy.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Palette for devices without Material You (API 24-30).
 *
 * Deliberately plain and few: every colour here is one a dynamic scheme would
 * supply on a modern device, so nothing downstream can depend on a token that
 * only exists in this file.
 */
val FallbackLight = lightColorScheme(
    primary = Color(0xFF2F6F4E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2F1C7),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4E6355),
    background = Color(0xFFFBFDF8),
    onBackground = Color(0xFF191C19),
    surface = Color(0xFFFBFDF8),
    onSurface = Color(0xFF191C19),
    surfaceVariant = Color(0xFFDCE5DB),
    onSurfaceVariant = Color(0xFF404943),
)

val FallbackDark = darkColorScheme(
    primary = Color(0xFF97D5AC),
    onPrimary = Color(0xFF003920),
    primaryContainer = Color(0xFF145232),
    onPrimaryContainer = Color(0xFFB2F1C7),
    secondary = Color(0xFFB5CCBB),
    background = Color(0xFF191C19),
    onBackground = Color(0xFFE1E3DE),
    surface = Color(0xFF191C19),
    onSurface = Color(0xFFE1E3DE),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC0C9C0),
)
