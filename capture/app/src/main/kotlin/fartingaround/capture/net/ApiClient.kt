package fartingaround.capture.net

import fartingaround.capture.model.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class ApiException(message: String, val status: Int? = null, val code: String? = null) : Exception(message)

/**
 * Client for the site's `/api/v1` authoring API (docs/api-v1.md on the web
 * side) plus the public bits the app reads. Story documents travel as
 * `JsonObject` on purpose — the phone edits them without modelling every
 * field, so nothing the web editor wrote is lost on a round trip.
 */
class ApiClient(val http: OkHttpClient = defaultHttp()) {

	companion object {
		fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
			.connectTimeout(30, TimeUnit.SECONDS)
			.readTimeout(120, TimeUnit.SECONDS)
			.writeTimeout(10, TimeUnit.MINUTES) // big video PUTs
			.build()

		private val JSON = "application/json; charset=utf-8".toMediaType()
	}

	/** Outcome of a conditional PUT. */
	sealed class PutResult {
		data class Saved(val story: JsonObject) : PutResult()
		/** The server has a newer copy; nothing was written. */
		data class Conflict(val current: JsonObject) : PutResult()
	}

	/** Bearer-authenticated base for one sync run. */
	inner class Session(private val base: String, private val token: String) {

		/**
		 * Bearer auth plus an Origin header matching the site: SvelteKit's CSRF
		 * guard rejects multipart POSTs whose Origin is missing or foreign, so
		 * we present ourselves as same-origin. The token is what authenticates.
		 */
		private fun auth(b: Request.Builder) = b
			.header("Authorization", "Bearer $token")
			.header("Origin", base)

		// ── Stories ───────────────────────────────────────────────────────────

		/** GET /api/v1/stories[?draft=1|0] → summaries. */
		suspend fun listStories(draft: Boolean? = null): List<JsonObject> {
			val q = when (draft) { true -> "?draft=1"; false -> "?draft=0"; null -> "" }
			val req = auth(Request.Builder().url("$base/api/v1/stories$q")).get().build()
			return execute(req, "list stories").jsonObject["stories"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
		}

		/** GET /api/v1/stories/:slug → the full author's copy. */
		suspend fun getStory(slug: String): JsonObject {
			val req = auth(Request.Builder().url("$base/api/v1/stories/$slug")).get().build()
			return execute(req, "get story").jsonObject["story"]!!.jsonObject
		}

		/** POST /api/v1/stories → the created story (server picks the slug). */
		suspend fun createStory(doc: JsonObject): JsonObject {
			val req = auth(Request.Builder().url("$base/api/v1/stories")).post(doc.toString().toRequestBody(JSON)).build()
			return execute(req, "create story").jsonObject["story"]!!.jsonObject
		}

		/**
		 * PUT /api/v1/stories/:slug, conditional on [ifMatch] (the `updatedAt`
		 * our copy came from). 409 comes back as [PutResult.Conflict] rather
		 * than an exception because it's an expected outcome, not a failure.
		 */
		suspend fun putStory(slug: String, doc: JsonObject, ifMatch: String?, force: Boolean = false): PutResult = withContext(Dispatchers.IO) {
			val url = "$base/api/v1/stories/$slug" + if (force) "?force=1" else ""
			val b = auth(Request.Builder().url(url)).put(doc.toString().toRequestBody(JSON))
			if (ifMatch != null && !force) b.header("If-Match", "\"$ifMatch\"")
			val res = try { http.newCall(b.build()).execute() } catch (e: Exception) {
				throw ApiException("Network error reaching $url: ${e.message}")
			}
			res.use {
				val text = it.body?.string() ?: ""
				val body = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull()
				when {
					it.code == 409 && body?.get("current") is JsonObject -> PutResult.Conflict(body["current"]!!.jsonObject)
					!it.isSuccessful -> throw ApiException("save story failed: ${it.code} ${text.take(200)}", it.code, body?.get("error")?.jsonPrimitive?.content)
					else -> PutResult.Saved(body!!["story"]!!.jsonObject)
				}
			}
		}

		/** DELETE /api/v1/stories/:slug — the story and every file it referenced. */
		suspend fun deleteStory(slug: String) {
			val req = auth(Request.Builder().url("$base/api/v1/stories/$slug")).delete().build()
			execute(req, "delete story")
		}

		// ── Media ─────────────────────────────────────────────────────────────

		/** POST /api/v1/media/photo → { imageId, w, h, takenAt?, exif? }. */
		suspend fun uploadPhoto(file: File, mimeType: String, filename: String): JsonObject {
			val body = MultipartBody.Builder().setType(MultipartBody.FORM)
				.addFormDataPart("file", filename, file.asRequestBody(mimeType.toMediaType()))
				.build()
			val req = auth(Request.Builder().url("$base/api/v1/media/photo")).post(body).build()
			return execute(req, "photo upload").jsonObject
		}

		/** POST /api/v1/media/sign for audio/video → { id, key, url, expiresAt }. */
		suspend fun signMedia(kind: String, mimeType: String, id: String): JsonObject {
			val payload = buildJsonObject { put("kind", kind); put("mimeType", mimeType); put("id", id) }
			val req = auth(Request.Builder().url("$base/api/v1/media/sign")).post(payload.toString().toRequestBody(JSON)).build()
			return execute(req, "sign upload").jsonObject
		}

		/** POST /api/v1/media/confirm — the server HEADs the keys; 409 if any is missing. */
		suspend fun confirmMedia(keys: List<String>) {
			val payload = buildJsonObject { put("keys", JsonArray(keys.map(::JsonPrimitive))) }
			val req = auth(Request.Builder().url("$base/api/v1/media/confirm")).post(payload.toString().toRequestBody(JSON)).build()
			execute(req, "confirm upload")
		}

		/** GET /api/stories/:slug/audio/:id — a clip back from the server (drafts need the token). */
		suspend fun fetchAudio(slug: String, id: String, into: File) = withContext(Dispatchers.IO) {
			val req = auth(Request.Builder().url("$base/api/stories/$slug/audio/$id")).get().build()
			http.newCall(req).execute().use { res ->
				if (!res.isSuccessful) throw ApiException("Couldn't fetch audio (${res.code}).", res.code)
				into.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
			}
		}

		// ── Other ─────────────────────────────────────────────────────────────

		/** Back the reader's subscription list up to the site as OPML. */
		suspend fun putFollowing(opml: String) {
			val body = opml.toRequestBody("text/x-opml; charset=utf-8".toMediaType())
			execute(auth(Request.Builder().url("$base/api/v1/following")).put(body).build(), "back up following")
		}

		/** GET /api/v1/settings → the name the owner's comments are signed with ('' = none chosen). */
		suspend fun authorName(): String {
			val req = auth(Request.Builder().url("$base/api/v1/settings")).get().build()
			return execute(req, "read site settings").jsonObject["authorName"]?.jsonPrimitive?.content ?: ""
		}

		/** PUT /api/v1/settings → the name as the site stored it. */
		suspend fun setAuthorName(name: String): String {
			val payload = buildJsonObject { put("authorName", name) }
			val req = auth(Request.Builder().url("$base/api/v1/settings")).put(payload.toString().toRequestBody(JSON)).build()
			return execute(req, "save your name").jsonObject["authorName"]?.jsonPrimitive?.content ?: ""
		}

		/** Settings → "Test connection": how many stories (drafts included) the server has. */
		suspend fun countStories(): Int = listStories().size

		private suspend fun execute(req: Request, what: String): JsonElement = withContext(Dispatchers.IO) {
			val res = try {
				http.newCall(req).execute()
			} catch (e: Exception) {
				throw ApiException("Network error reaching ${req.url} ($what): ${e.message}")
			}
			res.use {
				val text = it.body?.string() ?: ""
				if (!it.isSuccessful) {
					val code = runCatching { AppJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
					throw ApiException("$what failed: ${it.code} ${text.take(200)}", it.code, code)
				}
				if (text.isBlank()) JsonObject(emptyMap()) else AppJson.parseToJsonElement(text)
			}
		}
	}

	fun session(base: String, token: String) = Session(base.trimEnd('/'), token)

	/** PUT a file to a signed upload URL (no auth header; the signature is in the URL). */
	suspend fun signedPut(url: String, file: File, mimeType: String) = withContext(Dispatchers.IO) {
		val req = Request.Builder().url(url).put(file.asRequestBody(mimeType.toMediaType())).build()
		val res = try {
			http.newCall(req).execute()
		} catch (e: Exception) {
			throw ApiException("Upload failed (network): ${e.message}")
		}
		res.use {
			if (!it.isSuccessful) throw ApiException("Upload failed: ${it.code} ${it.body?.string()?.take(200)}", it.code)
		}
	}

	@Serializable
	data class LiveStory(
		val slug: String,
		val title: String,
		val date: String,
		val location: String? = null,
		val summary: String? = null,
		val tags: List<String> = emptyList(),
		val coverUrl: String? = null,
		val coverThumbUrl: String? = null,
		val href: String
	)

	/** GET /api/stories — public, no auth. For the Live tab. */
	suspend fun liveStories(base: String): List<LiveStory> = withContext(Dispatchers.IO) {
		val req = Request.Builder().url("${base.trimEnd('/')}/api/stories").get().build()
		http.newCall(req).execute().use { res ->
			if (!res.isSuccessful) throw ApiException("HTTP ${res.code}", res.code)
			val arr: JsonArray = AppJson.parseToJsonElement(res.body!!.string()).jsonObject["stories"]!!.jsonArray
			arr.map { AppJson.decodeFromJsonElement(LiveStory.serializer(), it) }
		}
	}
}
