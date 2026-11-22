package fartingaround.capture.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * AUTO follows the phone's light/dark setting (paper by day, ink by night).
 * ASTRO is the dim-red night-vision look and is always an explicit choice.
 */
enum class ThemeName { AUTO, PAPER, INK, ASTRO }

private fun parseTheme(v: String?): ThemeName = when (v) {
	"astro" -> ThemeName.ASTRO
	"ink" -> ThemeName.INK
	"paper" -> ThemeName.PAPER
	else -> ThemeName.AUTO
}

/** Which half of the app is showing: capture (drafts) or consume (the reader). */
enum class AppMode { CAPTURE, CONSUME }

/** Mirrors CaptureSettings in the TS app. */
data class CaptureSettings(
	val apiBase: String = "https://fartingaround.fun",
	val apiToken: String = "",
	val elevenLabsApiKey: String = "",
	val elevenLabsModel: String = "scribe_v1",
	val theme: ThemeName = ThemeName.AUTO,
	/** The last non-astro theme, so the top-bar toggle can return to it. */
	val dayTheme: ThemeName = ThemeName.AUTO,
	val mode: AppMode = AppMode.CAPTURE
) {
	/** apiBase with any trailing slash removed, ready for string concatenation. */
	val base: String get() = apiBase.trimEnd('/')
}

private val Context.store by preferencesDataStore(name = "capture-settings")

/**
 * Persistent app settings via Jetpack DataStore (the Preferences flavour).
 * Same role as Capacitor Preferences in the old app.
 */
class Settings(private val context: Context) {
	private object Keys {
		val apiBase = stringPreferencesKey("captureApiBase")
		val apiToken = stringPreferencesKey("captureApiToken")
		val elevenKey = stringPreferencesKey("elevenLabsApiKey")
		val elevenModel = stringPreferencesKey("elevenLabsModel")
		val theme = stringPreferencesKey("uiTheme")
		val dayTheme = stringPreferencesKey("uiThemeDay")
		val mode = stringPreferencesKey("appMode")
	}

	val flow: Flow<CaptureSettings> = context.store.data.map { p ->
		val defaults = CaptureSettings()
		val theme = parseTheme(p[Keys.theme])
		CaptureSettings(
			apiBase = p[Keys.apiBase]?.takeIf { it.isNotBlank() } ?: defaults.apiBase,
			apiToken = p[Keys.apiToken] ?: "",
			elevenLabsApiKey = p[Keys.elevenKey] ?: "",
			elevenLabsModel = p[Keys.elevenModel]?.takeIf { it.isNotBlank() } ?: defaults.elevenLabsModel,
			theme = theme,
			dayTheme = p[Keys.dayTheme]?.let(::parseTheme)?.takeIf { it != ThemeName.ASTRO }
				?: theme.takeIf { it != ThemeName.ASTRO }
				?: ThemeName.AUTO,
			mode = if (p[Keys.mode] == "consume") AppMode.CONSUME else AppMode.CAPTURE
		)
	}

	suspend fun load(): CaptureSettings = flow.first()

	/** Writes everything except the theme, which has its own setter (the top-bar toggle). */
	suspend fun save(s: CaptureSettings) {
		context.store.edit { p ->
			p[Keys.apiBase] = s.apiBase.trim()
			p[Keys.apiToken] = s.apiToken.trim()
			p[Keys.elevenKey] = s.elevenLabsApiKey.trim()
			p[Keys.elevenModel] = s.elevenLabsModel.trim()
		}
	}

	suspend fun setMode(mode: AppMode) {
		context.store.edit { p -> p[Keys.mode] = if (mode == AppMode.CONSUME) "consume" else "capture" }
	}

	/** Applies immediately (top-bar toggle and the Settings chips); leaves the other settings alone. */
	suspend fun setTheme(theme: ThemeName) {
		context.store.edit { p ->
			val key = theme.name.lowercase()
			p[Keys.theme] = key
			if (theme != ThemeName.ASTRO) p[Keys.dayTheme] = key
		}
	}
}
