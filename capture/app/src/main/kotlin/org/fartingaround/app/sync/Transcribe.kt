package org.fartingaround.app.sync

import org.fartingaround.app.data.CaptureSettings
import org.fartingaround.app.model.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * ElevenLabs Scribe speech-to-text. The recording goes from the device
 * straight to api.elevenlabs.io with the user's own key, never through the
 * site. Docs: https://elevenlabs.io/docs/api-reference/speech-to-text/convert
 */
object Transcribe {
	class TranscribeException(message: String, val status: Int? = null) : Exception(message)

	/** One timed word: the text, start and end in seconds — the shape the site stores and the lightbox lights up. */
	data class Word(val t: String, val s: Double, val e: Double)

	data class Result(val text: String, val model: String, val languageCode: String?, val words: List<Word> = emptyList()) {
		/** `[{ t, s, e }]` for a block's `words` field. */
		fun wordsJson(): JsonArray = JsonArray(words.map { w ->
			JsonObject(mapOf("t" to JsonPrimitive(w.t), "s" to JsonPrimitive(w.s), "e" to JsonPrimitive(w.e)))
		})
	}

	suspend fun run(http: OkHttpClient, settings: CaptureSettings, audio: File, mimeType: String): Result =
		withContext(Dispatchers.IO) {
			if (settings.elevenLabsApiKey.isBlank()) throw TranscribeException("No ElevenLabs API key set. Open Settings.")
			val model = settings.elevenLabsModel.ifBlank { "scribe_v1" }
			val body = MultipartBody.Builder().setType(MultipartBody.FORM)
				.addFormDataPart("file", "clip." + if (mimeType.contains("mp4")) "m4a" else "webm", audio.asRequestBody(mimeType.toMediaType()))
				.addFormDataPart("model_id", model)
				.addFormDataPart("timestamps_granularity", "word")
				.addFormDataPart("tag_audio_events", "false")
				.build()
			val req = Request.Builder().url("https://api.elevenlabs.io/v1/speech-to-text")
				.header("xi-api-key", settings.elevenLabsApiKey)
				.post(body).build()
			http.newCall(req).execute().use { res ->
				val text = res.body?.string() ?: ""
				if (!res.isSuccessful) throw TranscribeException("ElevenLabs ${res.code}: ${text.take(300)}", res.code)
				val obj = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull()
					?: throw TranscribeException("Unexpected response shape from ElevenLabs.")
				val words = (obj["words"] as? JsonArray)?.mapNotNull { el ->
					val w = el as? JsonObject ?: return@mapNotNull null
					val type = w["type"]?.jsonPrimitive?.contentOrNull
					if (type != null && type != "word") return@mapNotNull null
					val t = w["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
					Word(t, round2(w["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0), round2(w["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0))
				} ?: emptyList()
				Result(
					text = obj["text"]?.jsonPrimitive?.content ?: "",
					model = model,
					languageCode = obj["language_code"]?.jsonPrimitive?.content,
					words = words
				)
			}
		}
}

private fun round2(d: Double) = Math.round(d * 100) / 100.0
