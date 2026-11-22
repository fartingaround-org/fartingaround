package fartingaround.capture.consume

import fartingaround.capture.model.AppJson
import fartingaround.capture.model.bool
import fartingaround.capture.model.objs
import fartingaround.capture.model.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One comment as the site shows it. [photoId] is the dump photo it is about, if any. */
data class Comment(
	val id: String,
	val name: String,
	val byOwner: Boolean,
	val body: String,
	val createdAt: String,
	val deleted: Boolean,
	val photoId: String?,
	val replies: List<Comment>
)

/**
 * A story's comment section. [enabled] false means there is none for this
 * reader. [canPost] is true only for the site's owner (see below).
 */
data class CommentThread(val enabled: Boolean, val canPost: Boolean, val postAs: String?, val comments: List<Comment>) {
	/** Everything said, replies included. */
	val total: Int get() = comments.sumOf { (if (it.deleted) 0 else 1) + it.replies.size }

	/** Comments about one photo, oldest first. */
	fun about(imageId: String): List<Comment> = comments.filter { it.photoId == imageId }

	/** How many comments (replies included) a photo has. */
	fun countAbout(imageId: String): Int = about(imageId).sumOf { (if (it.deleted) 0 else 1) + it.replies.size }
}

/**
 * `/api/v1/stories/:slug/comments` (docs/api-v1.md on the web side).
 *
 * Reading: sent with the capture token when the story is on the owner's own
 * site ([ownerToken]), else with the story's reader token if it came from a
 * personal feed, else with nothing (the public view).
 *
 * Writing: the owner only, on their own site. The server refuses a reader
 * token, because a feed address can leak; readers comment on the site.
 */
object CommentsApi {
	private val JSON = "application/json; charset=utf-8".toMediaType()

	private fun url(story: Story, ownerToken: String?): String {
		val base = "${story.base}/api/v1/stories/${story.slug}/comments"
		val t = story.token
		return if (ownerToken == null && t != null) base + "?token=" + java.net.URLEncoder.encode(t, "UTF-8") else base
	}

	private fun Request.Builder.owner(story: Story, ownerToken: String?): Request.Builder =
		if (ownerToken != null) header("Authorization", "Bearer $ownerToken").header("Origin", story.base) else this

	/** Null when the story has no comment section for us, or the site can't be reached. */
	suspend fun load(http: OkHttpClient, story: Story, ownerToken: String?): CommentThread? = withContext(Dispatchers.IO) {
		runCatching {
			val req = Request.Builder().url(url(story, ownerToken)).owner(story, ownerToken).get().build()
			http.newCall(req).execute().use { res ->
				if (!res.isSuccessful) return@use null
				val o = AppJson.parseToJsonElement(res.body?.string() ?: return@use null).jsonObject
				CommentThread(
					enabled = o.bool("enabled") == true,
					canPost = o.bool("canPost") == true,
					postAs = o.str("as"),
					comments = o.objs("comments").mapNotNull(::comment)
				)
			}
		}.getOrNull()
	}

	/** Posts as the owner. Throws with the site's own message when it is refused. */
	suspend fun post(http: OkHttpClient, story: Story, ownerToken: String, body: String, parentId: String? = null, photoId: String? = null) = withContext(Dispatchers.IO) {
		val payload = buildJsonObject {
			put("body", body)
			if (parentId != null) put("parentId", parentId)
			if (photoId != null) put("photoId", photoId)
		}
		val req = Request.Builder().url(url(story, ownerToken)).owner(story, ownerToken).post(payload.toString().toRequestBody(JSON)).build()
		http.newCall(req).execute().use { res ->
			if (!res.isSuccessful) {
				val msg = runCatching { AppJson.parseToJsonElement(res.body?.string() ?: "").jsonObject.str("message") }.getOrNull()
				throw IllegalStateException(msg ?: "Couldn't post the comment (${res.code}).")
			}
		}
	}

	private fun comment(o: JsonObject): Comment? {
		return Comment(
			id = o.str("id") ?: return null,
			name = o.str("name") ?: "",
			byOwner = o.bool("byOwner") == true,
			body = o.str("body") ?: "",
			createdAt = o.str("createdAt") ?: "",
			deleted = o.bool("deleted") == true,
			photoId = o.str("photoId"),
			replies = o.objs("replies").mapNotNull(::comment)
		)
	}
}
