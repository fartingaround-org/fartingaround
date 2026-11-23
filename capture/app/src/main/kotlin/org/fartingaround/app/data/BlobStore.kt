package org.fartingaround.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Raw media bytes, one file per blob under filesDir/blobs/. The attachment that
 * owns a blob records its id in `localBlobId` and remembers the MIME type, so
 * the files themselves carry no extension.
 *
 * filesDir is private to the app and never scanned by the gallery, which is
 * what we want: drafts are a staging area, the canonical copy is the server.
 */
class BlobStore(private val context: Context) {
	private val dir = File(context.filesDir, "blobs").apply { mkdirs() }

	fun newId(): String = "blob-" + UUID.randomUUID()

	fun file(id: String): File = File(dir, id)

	fun exists(id: String): Boolean = file(id).isFile

	/** Copy the bytes behind a content:// or file:// Uri into a new blob. */
	suspend fun importUri(uri: Uri, resolver: ContentResolver = context.contentResolver): String =
		withContext(Dispatchers.IO) {
			val id = newId()
			resolver.openInputStream(uri)?.use { input ->
				file(id).outputStream().use { out -> input.copyTo(out) }
			} ?: throw IllegalStateException("Could not open $uri")
			id
		}

	/** Adopt an existing file (e.g. a finished recording) by moving it in. */
	suspend fun adoptFile(src: File): String = withContext(Dispatchers.IO) {
		val id = newId()
		val dest = file(id)
		if (!src.renameTo(dest)) {
			src.copyTo(dest, overwrite = true)
			src.delete()
		}
		id
	}

	suspend fun delete(id: String) = withContext(Dispatchers.IO) {
		file(id).delete()
		Unit
	}

	/** A file:// Uri the app itself can hand to ExoPlayer / Coil. */
	fun uri(id: String): Uri = Uri.fromFile(file(id))
}
