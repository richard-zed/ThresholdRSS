package rip.failed.daisy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import rip.failed.daisy.R

/**
 * Quicksand, bundled rather than downloaded.
 *
 * Four static weights are shipped instead of the variable font because
 * androidx.compose.ui.text.font.Font takes a single face per weight; a
 * variable axis would need FontVariation.Settings threaded through every
 * style. Four weights cover the whole M3 Typography ramp, which only asks
 * for Normal, Medium and Bold.
 *
 * The family is named here and renamed nothing: Quicksand's OFL carries a
 * Reserved Font Name, so the files must not be altered or rebranded. They
 * are shipped byte-identical, and the licence sits at QUICKSAND-OFL.txt in
 * the repo root -- NOT in res/font, where aapt treats every file as a
 * resource and a .txt fails the build.
 */
val Quicksand = FontFamily(
    Font(R.font.quicksand_regular, FontWeight.Normal),
    Font(R.font.quicksand_medium, FontWeight.Medium),
    Font(R.font.quicksand_semibold, FontWeight.SemiBold),
    Font(R.font.quicksand_bold, FontWeight.Bold),
)

/**
 * The M3 ramp with Quicksand swapped in.
 *
 * Built from `Typography()` and copied field by field rather than written
 * out: the default ramp carries size, line height, letter spacing and
 * tracking that were tuned together, and retyping them by hand is how a
 * body line ends up with a display's letter spacing. Only the family is
 * changed. Weights and sizes stay M3's, so nothing that reads
 * `typography.bodySmall` today changes shape.
 */
private fun quicksand(base: TextStyle) = base.copy(fontFamily = Quicksand)

val DaisyTypography = Typography().let { t ->
    Typography(
        displayLarge = quicksand(t.displayLarge),
        displayMedium = quicksand(t.displayMedium),
        displaySmall = quicksand(t.displaySmall),
        headlineLarge = quicksand(t.headlineLarge),
        headlineMedium = quicksand(t.headlineMedium),
        headlineSmall = quicksand(t.headlineSmall),
        titleLarge = quicksand(t.titleLarge),
        titleMedium = quicksand(t.titleMedium),
        titleSmall = quicksand(t.titleSmall),
        bodyLarge = quicksand(t.bodyLarge),
        bodyMedium = quicksand(t.bodyMedium),
        bodySmall = quicksand(t.bodySmall),
        labelLarge = quicksand(t.labelLarge),
        labelMedium = quicksand(t.labelMedium),
        labelSmall = quicksand(t.labelSmall),
    )
}
