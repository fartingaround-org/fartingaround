package org.fartingaround.app.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
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

/**
 * [apiBase] is the owner's own site and starts empty: the app has no site
 * built in, and Create asks for one before it does anything (ConnectScreen).
 */
data class CaptureSettings(
	val apiBase: String = "",
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

	/** The site as people say it: "example.org". Empty until a site is connected. */
	val siteHost: String get() = base.substringAfter("://")
}

private val Context.store by preferencesDataStore(name = "capture-settings")

/**
 * Persistent app settings via Jetpack DataStore (the Preferences flavour).
 * Same role as Capacitor Preferences in the old app.
 */
class Settings(private val context: Context) {
	private object Keys {
		val apiBase = stringPreferencesKey("captureApiBase")
		// The two secrets are stored encrypted (Secrets.kt) under the "Sealed"
		// names. The plain names are what older builds wrote; they are read once
		// more, then replaced.
		val apiToken = stringPreferencesKey("captureApiToken")
		val elevenKey = stringPreferencesKey("elevenLabsApiKey")
		val apiTokenSealed = stringPreferencesKey("captureApiTokenSealed")
		val elevenKeySealed = stringPreferencesKey("elevenLabsApiKeySealed")
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
			apiToken = secret(p[Keys.apiTokenSealed], p[Keys.apiToken]),
			elevenLabsApiKey = secret(p[Keys.elevenKeySealed], p[Keys.elevenKey]),
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
			putSecret(p, Keys.apiTokenSealed, Keys.apiToken, s.apiToken.trim())
			putSecret(p, Keys.elevenKeySealed, Keys.elevenKey, s.elevenLabsApiKey.trim())
			p[Keys.elevenModel] = s.elevenLabsModel.trim()
		}
	}

	/** The last values decrypted, so the many readers of [flow] don't each ask the keystore again. */
	private val opened = java.util.concurrent.ConcurrentHashMap<String, String>()

	/** A secret as it should be used: the encrypted copy when there is one, else what an older build left. */
	private fun secret(sealed: String?, plain: String?): String {
		if (!sealed.isNullOrEmpty()) {
			opened[sealed]?.let { return it }
			Secrets.open(sealed)?.let { opened[sealed] = it; return it }
		}
		return plain ?: ""
	}

	/** Stores [value] encrypted and drops any plain copy. If the keystore can't be used, it is kept as before. */
	private fun putSecret(p: MutablePreferences, sealedKey: Preferences.Key<String>, plainKey: Preferences.Key<String>, value: String) {
		val sealed = if (value.isEmpty()) null else Secrets.seal(value)
		when {
			value.isEmpty() -> { p.remove(sealedKey); p.remove(plainKey) }
			sealed != null -> { p[sealedKey] = sealed; p.remove(plainKey) }
			else -> { p.remove(sealedKey); p[plainKey] = value }
		}
	}

	/** Encrypts secrets an older build stored as plain text. Run once at start; does nothing afterwards. */
	suspend fun sealSecrets() {
		context.store.edit { p ->
			p[Keys.apiToken]?.takeIf { it.isNotEmpty() }?.let { putSecret(p, Keys.apiTokenSealed, Keys.apiToken, it) }
			p[Keys.elevenKey]?.takeIf { it.isNotEmpty() }?.let { putSecret(p, Keys.elevenKeySealed, Keys.elevenKey, it) }
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
