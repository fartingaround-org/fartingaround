package org.fartingaround.app.data

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Small helpers for reading media metadata off local files. All best-effort. */
object MediaInfo {

	data class Dims(val w: Int, val h: Int)

	/**
	 * Pixel dimensions as displayed — i.e. with the EXIF orientation applied,
	 * so a portrait phone photo reports portrait even though the JPEG's raw
	 * pixel grid is landscape. Cloudflare Images applies the same rotation on
	 * delivery, so this matches what the site will show.
	 */
	suspend fun imageDims(file: File): Dims? = withContext(Dispatchers.IO) {
		runCatching {
			val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			BitmapFactory.decodeFile(file.path, opts)
			if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching null
			val rotated = isRotated90(file)
			if (rotated) Dims(opts.outHeight, opts.outWidth) else Dims(opts.outWidth, opts.outHeight)
		}.getOrNull()
	}

	/** Where the photo says it was taken (latitude, longitude), when its EXIF carries a position. */
	fun gps(file: File): Pair<Double, Double>? = runCatching {
		ExifInterface(file).latLong?.let { it[0] to it[1] }
	}.getOrNull()

	/** True when EXIF says the image is turned 90°/270°. */
	fun isRotated90(file: File): Boolean = runCatching {
		when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
			ExifInterface.ORIENTATION_ROTATE_90,
			ExifInterface.ORIENTATION_ROTATE_270,
			ExifInterface.ORIENTATION_TRANSPOSE,
			ExifInterface.ORIENTATION_TRANSVERSE -> true
			else -> false
		}
	}.getOrDefault(false)

	/** Duration of an audio or video file in milliseconds. */
	suspend fun durationMs(file: File): Long? = withContext(Dispatchers.IO) {
		runCatching {
			MediaMetadataRetriever().use { r ->
				r.setDataSource(file.path)
				r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
			}
		}.getOrNull()
	}

	/**
	 * The container a media file really is, read from its first bytes. Android's
	 * share sheet can mislabel a file (Photos has handed us an .mp4 as
	 * "video/mpeg"), and the site only accepts types a browser can play, so trust
	 * the bytes over the label. Falls back to [declared] when the header isn't
	 * one we recognise.
	 */
	fun containerMime(file: File, kind: String, declared: String): String = runCatching {
		val head = ByteArray(12)
		val n = file.inputStream().use { it.read(head) }
		when {
			// MP4 family: bytes 4..7 are "ftyp", 8..11 the brand ("qt  " is QuickTime .mov).
			n >= 12 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> when {
				kind == "audio" -> "audio/mp4"
				String(head, 8, 4, Charsets.US_ASCII) == "qt  " -> "video/quicktime"
				else -> "video/mp4"
			}
			// WebM / Matroska: EBML magic.
			n >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> "$kind/webm"
			else -> declared
		}
	}.getOrDefault(declared)

	/** Pick a sensible file extension for a MIME type (for upload filenames). */
	fun extFor(mime: String): String = when {
		mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
		mime.contains("png") -> "png"
		mime.contains("webp") -> "webp"
		mime.contains("heic") || mime.contains("heif") -> "heic"
		mime.contains("gif") -> "gif"
		mime.contains("mp4") && mime.startsWith("audio") -> "m4a"
		mime.contains("mp4") -> "mp4"
		mime.contains("webm") -> "webm"
		mime.contains("3gpp") -> "3gp"
		mime.contains("ogg") -> "ogg"
		else -> "bin"
	}
}
