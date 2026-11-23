package org.fartingaround.app.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Downscaled JPEG derivatives for full-resolution (R2) photos. Mirrors
 * image-derivatives.ts: a few standard widths plus one at the image's own
 * width (capped), so the lightbox has a near-full-res view without pulling the
 * raw original. The original is never re-encoded.
 *
 * Decoding a 50 MP astro frame at full size would need ~200 MB of heap, so the
 * largest derivative is decoded with inSampleSize first and then scaled the
 * rest of the way. Each derivative is written to cacheDir and the caller
 * deletes them after upload.
 */
object ImageDerivatives {
	private val STANDARD_WIDTHS = listOf(480, 960, 1440, 2048)
	private const val MAX_DERIVATIVE = 4096
	private const val JPEG_QUALITY = 85

	data class Derivative(val width: Int, val file: File)
	data class Result(
		val ext: String,
		val w: Int,
		val h: Int,
		val widths: List<Int>,
		val original: File,
		val derivatives: List<Derivative>
	)

	suspend fun generate(original: File, mimeType: String, workDir: File): Result = withContext(Dispatchers.Default) {
		workDir.mkdirs()
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(original.path, bounds)
		val exif = runCatching { ExifInterface(original) }.getOrNull()
		val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
			?: ExifInterface.ORIENTATION_NORMAL
		val rotated = orientation in setOf(
			ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
			ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE
		)
		// Displayed dimensions (orientation applied), like the TS `from-image` bitmap.
		val w = if (rotated) bounds.outHeight else bounds.outWidth
		val h = if (rotated) bounds.outWidth else bounds.outHeight
		require(w > 0 && h > 0) { "Could not decode image" }

		val targets = (STANDARD_WIDTHS.filter { it < w } + min(w, MAX_DERIVATIVE)).distinct().sorted()

		// Decode once at roughly the largest target (power-of-two sampling),
		// oriented upright, then scale down for each width from that bitmap.
		val largest = targets.last()
		val sample = BitmapFactory.Options().apply {
			inSampleSize = sampleSizeFor(w, largest)
			inPreferredConfig = Bitmap.Config.ARGB_8888
		}
		val decoded = BitmapFactory.decodeFile(original.path, sample) ?: error("Could not decode image")
		val upright = applyOrientation(decoded, orientation)

		val derivatives = targets.map { tw ->
			val th = max(1, (tw.toDouble() * h / w).roundToInt())
			val scaled = if (upright.width == tw) upright else Bitmap.createScaledBitmap(upright, tw, th, true)
			val f = File(workDir, "w$tw.jpg")
			f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
			if (scaled !== upright) scaled.recycle()
			Derivative(tw, f)
		}
		upright.recycle()

		Result(
			ext = extFor(original.name, mimeType),
			w = w, h = h,
			widths = derivatives.map { it.width },
			original = original,
			derivatives = derivatives
		)
	}

	/** Largest power of two such that the decoded (displayed) width stays ≥ target. */
	private fun sampleSizeFor(displayedWidth: Int, target: Int): Int {
		var s = 1
		while (displayedWidth / (s * 2) >= target) s *= 2
		return s
	}

	private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
		val m = Matrix()
		when (orientation) {
			ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
			ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
			ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
			ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.preScale(-1f, 1f)
			ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.preScale(1f, -1f)
			ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.preScale(-1f, 1f) }
			ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.preScale(-1f, 1f) }
			else -> return src
		}
		val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
		if (out !== src) src.recycle()
		return out
	}

	fun extFor(name: String, mime: String): String {
		val fromName = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
		if (fromName.isNotEmpty() && fromName.length <= 5) return if (fromName == "jpeg") "jpg" else fromName
		return when {
			mime.contains("png") -> "png"
			mime.contains("webp") -> "webp"
			mime.contains("avif") -> "avif"
			else -> "jpg"
		}
	}
}
