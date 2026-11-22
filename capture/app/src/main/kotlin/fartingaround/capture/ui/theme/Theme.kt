package fartingaround.capture.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import fartingaround.capture.data.ThemeName

/*
 * Three palettes. Paper and ink mirror the site's src/app.css (keep them in step):
 *   paper — the postal/literary look of the site
 *   ink   — the same look at night: warm near-black paper, cream ink
 *   astro — near-black with dim red ink for use at the telescope, so the screen
 *           doesn't wreck dark-adapted eyes. Images get a red tint too
 *           (see astroImageFilter).
 */

object Paper {
	val paper = Color(0xFFF5EFE1)
	val paperDeep = Color(0xFFECE4D0)
	val ink = Color(0xFF1B1A16)
	val inkSoft = Color(0xFF3C382E)
	val pencil = Color(0xFF6E6A5E)
	val pencilSoft = Color(0xFFA39E8E)
	val wax = Color(0xFFB3322A)
	val postmark = Color(0xFF2A3A52)
	val stamp = Color(0xFFD9B96A)
}

object Ink {
	val paper = Color(0xFF171512)
	val paperDeep = Color(0xFF24211C)
	val ink = Color(0xFFECE4D0)
	val inkSoft = Color(0xFFD2CBB9)
	val pencil = Color(0xFFB0AA99)
	val pencilSoft = Color(0xFF6B665A)
	val wax = Color(0xFFE2655A)
	val postmark = Color(0xFF9FB4D6)
	val stamp = Color(0xFFD9B96A)
}

object AstroRed {
	val paper = Color(0xFF050000)
	val paperDeep = Color(0xFF110404)
	val ink = Color(0xFFC40000)
	val inkSoft = Color(0xFF8A0000)
	val pencil = Color(0xFF7A0000)
	val pencilSoft = Color(0xFF3A0000)
	val wax = Color(0xFFFF2A2A)
	val postmark = Color(0xFFD23030)
	val stamp = Color(0xFF802020)
}

/** Semantic extras Material3's scheme has no slot for. */
data class CaptureColors(
	val pencil: Color,
	val pencilSoft: Color,
	val wax: Color,
	val postmark: Color,
	val stamp: Color,
	val isAstro: Boolean,
	/** True for ink and astro: anything that needs to know it sits on a dark ground. */
	val isDark: Boolean = isAstro
)

val LocalCaptureColors = staticCompositionLocalOf {
	CaptureColors(Paper.pencil, Paper.pencilSoft, Paper.wax, Paper.postmark, Paper.stamp, isAstro = false)
}

private val paperScheme: ColorScheme = lightColorScheme(
	primary = Paper.wax,
	onPrimary = Paper.paper,
	secondary = Paper.postmark,
	onSecondary = Paper.paper,
	tertiary = Paper.stamp,
	background = Paper.paper,
	onBackground = Paper.ink,
	surface = Paper.paper,
	onSurface = Paper.ink,
	surfaceVariant = Paper.paperDeep,
	onSurfaceVariant = Paper.inkSoft,
	outline = Paper.pencilSoft,
	outlineVariant = Color(0xFFD6CCB4),
	primaryContainer = Color(0xFFDDD3BA),
	onPrimaryContainer = Paper.ink,
	secondaryContainer = Color(0xFFDDD3BA),
	onSecondaryContainer = Paper.ink,
	tertiaryContainer = Color(0xFFDDD3BA),
	onTertiaryContainer = Paper.ink,
	surfaceDim = Paper.paper,
	surfaceBright = Paper.paperDeep,
	surfaceContainerLowest = Paper.paper,
	surfaceContainerLow = Paper.paper,
	surfaceContainer = Paper.paperDeep,
	surfaceContainerHigh = Paper.paperDeep,
	surfaceContainerHighest = Paper.paperDeep,
	error = Paper.wax,
	onError = Paper.paper
)

private val inkScheme: ColorScheme = darkColorScheme(
	primary = Ink.wax,
	onPrimary = Ink.paper,
	secondary = Ink.postmark,
	onSecondary = Ink.paper,
	tertiary = Ink.stamp,
	background = Ink.paper,
	onBackground = Ink.ink,
	surface = Ink.paper,
	onSurface = Ink.ink,
	surfaceVariant = Ink.paperDeep,
	onSurfaceVariant = Ink.inkSoft,
	outline = Ink.pencilSoft,
	outlineVariant = Color(0xFF45413A),
	primaryContainer = Color(0xFF3A362E),
	onPrimaryContainer = Ink.ink,
	secondaryContainer = Color(0xFF3A362E),
	onSecondaryContainer = Ink.ink,
	tertiaryContainer = Color(0xFF3A362E),
	onTertiaryContainer = Ink.ink,
	surfaceDim = Ink.paper,
	surfaceBright = Ink.paperDeep,
	surfaceContainerLowest = Ink.paper,
	surfaceContainerLow = Ink.paper,
	surfaceContainer = Ink.paperDeep,
	surfaceContainerHigh = Ink.paperDeep,
	surfaceContainerHighest = Ink.paperDeep,
	error = Ink.wax,
	onError = Ink.paper
)

private val astroScheme: ColorScheme = darkColorScheme(
	primary = AstroRed.wax,
	onPrimary = AstroRed.paper,
	secondary = AstroRed.postmark,
	onSecondary = AstroRed.paper,
	tertiary = AstroRed.stamp,
	background = AstroRed.paper,
	onBackground = AstroRed.ink,
	surface = AstroRed.paper,
	onSurface = AstroRed.ink,
	surfaceVariant = AstroRed.paperDeep,
	onSurfaceVariant = AstroRed.inkSoft,
	outline = AstroRed.pencilSoft,
	outlineVariant = AstroRed.pencilSoft,
	primaryContainer = AstroRed.pencilSoft,
	onPrimaryContainer = AstroRed.ink,
	secondaryContainer = AstroRed.pencilSoft,
	onSecondaryContainer = AstroRed.ink,
	tertiaryContainer = AstroRed.pencilSoft,
	onTertiaryContainer = AstroRed.ink,
	surfaceDim = AstroRed.paper,
	surfaceBright = AstroRed.paperDeep,
	surfaceContainerLowest = AstroRed.paper,
	surfaceContainerLow = AstroRed.paper,
	surfaceContainer = AstroRed.paperDeep,
	surfaceContainerHigh = AstroRed.paperDeep,
	surfaceContainerHighest = AstroRed.paperDeep,
	error = AstroRed.wax,
	onError = AstroRed.paper
)

/** Display = italic serif (titles), labels = mono caps (eyebrows), body = default sans. */
val CaptureTypography = Typography(
	headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 36.sp),
	headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
	titleMedium = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 24.sp),
	labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.2.sp, lineHeight = 16.sp),
	labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp, lineHeight = 16.sp),
	labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 1.sp, lineHeight = 18.sp),
	bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
	bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
	bodySmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
)

@Composable
fun CaptureTheme(theme: ThemeName, content: @Composable () -> Unit) {
	val astro = theme == ThemeName.ASTRO
	val ink = theme == ThemeName.INK || (theme == ThemeName.AUTO && isSystemInDarkTheme())
	val dark = astro || ink
	val extras = when {
		astro -> CaptureColors(AstroRed.pencil, AstroRed.pencilSoft, AstroRed.wax, AstroRed.postmark, AstroRed.stamp, isAstro = true)
		ink -> CaptureColors(Ink.pencil, Ink.pencilSoft, Ink.wax, Ink.postmark, Ink.stamp, isAstro = false, isDark = true)
		else -> CaptureColors(Paper.pencil, Paper.pencilSoft, Paper.wax, Paper.postmark, Paper.stamp, isAstro = false)
	}

	// The theme can differ from the phone's own light/dark setting, so tell the
	// system bars which icon colour to use rather than letting them guess.
	val view = LocalView.current
	if (!view.isInEditMode) {
		SideEffect {
			val window = (view.context as? Activity)?.window ?: return@SideEffect
			WindowCompat.getInsetsController(window, view).apply {
				isAppearanceLightStatusBars = !dark
				isAppearanceLightNavigationBars = !dark
			}
		}
	}

	CompositionLocalProvider(LocalCaptureColors provides extras) {
		MaterialTheme(
			colorScheme = if (astro) astroScheme else if (ink) inkScheme else paperScheme,
			typography = CaptureTypography,
			content = content
		)
	}
}

/**
 * Night-vision tint for photos/thumbnails: luminance → dim red, nothing in the
 * green/blue channels. Equivalent in spirit to the CSS
 * `grayscale(1) sepia(1) hue-rotate(-50deg) saturate(6) brightness(0.7)`.
 */
val astroImageFilter: ColorFilter = ColorFilter.colorMatrix(
	ColorMatrix(
		floatArrayOf(
			0.21f, 0.41f, 0.08f, 0f, 0f,  // R = 0.7 × luminance
			0f, 0f, 0f, 0f, 0f,           // G
			0f, 0f, 0f, 0f, 0f,           // B
			0f, 0f, 0f, 1f, 0f            // A
		)
	)
)

/** The image filter to apply in the current theme, or null in paper mode. */
@Composable
fun imageFilter(): ColorFilter? = if (LocalCaptureColors.current.isAstro) astroImageFilter else null

