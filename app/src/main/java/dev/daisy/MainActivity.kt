package dev.daisy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.daisy.ui.DaisyApp
import dev.daisy.ui.theme.DaisyTypography
import dev.daisy.ui.theme.FallbackDark
import dev.daisy.ui.theme.FallbackLight

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DaisyTheme { DaisyApp() } }
    }
}

/**
 * Material You when the device has it, our own palette when it does not.
 *
 * `dynamicLightColorScheme` reads the wallpaper-derived palette on Android 12+;
 * below that it throws, so the version check is load-bearing rather than
 * defensive. 31 is the constant for S, written out to avoid importing
 * Build.VERSION_CODES for one comparison.
 */
@Composable
fun DaisyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        android.os.Build.VERSION.SDK_INT >= 31 ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = DaisyTypography,
        content = content,
    )
}
