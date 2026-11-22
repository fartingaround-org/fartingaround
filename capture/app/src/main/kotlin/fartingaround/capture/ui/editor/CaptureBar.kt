package fartingaround.capture.ui.editor

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import fartingaround.capture.capture.AudioRecorder
import fartingaround.capture.captureApp
import fartingaround.capture.data.MediaInfo
import fartingaround.capture.ui.components.ErrorText
import fartingaround.capture.ui.components.fmtDuration
import fartingaround.capture.ui.theme.LocalCaptureColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Something just captured: bytes in the blob store plus what we know about them. The editor turns it into blocks. */
data class Captured(
	/** photo | audio | video */
	val kind: String,
	val blobId: String,
	val mimeType: String,
	val w: Int? = null,
	val h: Int? = null,
	/** Latitude and longitude from the photo's EXIF, when it has them. */
	val place: Pair<Double, Double>? = null,
	val durationMs: Long? = null
)

/**
 * The row of capture buttons: record audio, take/pick photos, record/pick video.
 *
 * Photos and videos are taken by the *system camera app* (TakePicture /
 * CaptureVideo) writing into a FileProvider URI in our private storage. That
 * gives us the phone's full-resolution output and all its computational
 * photography, which a hand-rolled in-app camera would lose. Library picks use
 * the Android Photo Picker, which needs no storage permission.
 */
@Composable
fun CaptureBar(onCapture: (Captured) -> Unit) {
	val context = LocalContext.current
	val app = context.captureApp
	val scope = rememberCoroutineScope()
	val c = LocalCaptureColors.current
	var error by remember { mutableStateOf<String?>(null) }

	// ── Audio ───────────────────────────────────────────────────────────────
	val recorder = remember { AudioRecorder(context) }
	var recording by remember { mutableStateOf(false) }
	var elapsedMs by remember { mutableLongStateOf(0L) }
	DisposableEffect(Unit) { onDispose { if (recorder.isRecording) recorder.cancel() } }
	LaunchedEffect(recording) {
		val started = System.currentTimeMillis()
		while (recording) {
			elapsedMs = System.currentTimeMillis() - started
			delay(250)
		}
	}

	fun startRecording() {
		try {
			recorder.start(); recording = true; error = null
		} catch (e: Exception) {
			error = "Couldn't start recording: ${e.message}"
		}
	}

	fun stopRecording() {
		recording = false
		scope.launch {
			try {
				val r = recorder.stop()
				val blobId = app.blobStore.adoptFile(r.file)
				onCapture(Captured("audio", blobId, r.mimeType, durationMs = r.durationMs))
			} catch (e: Exception) {
				error = "Recording failed: ${e.message}"
			}
		}
	}

	val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if (granted) startRecording() else error = "Microphone permission denied."
	}

	// ── Photo / video intake ────────────────────────────────────────────────
	suspend fun addPhotoFromUri(uri: Uri, mimeHint: String?) {
		val blobId = app.blobStore.importUri(uri)
		val file = app.blobStore.file(blobId)
		val dims = MediaInfo.imageDims(file)
		onCapture(Captured("photo", blobId, mimeHint ?: context.contentResolver.getType(uri) ?: "image/jpeg", w = dims?.w, h = dims?.h, place = MediaInfo.gps(file)))
	}

	suspend fun addVideoFromUri(uri: Uri, mimeHint: String?) {
		val blobId = app.blobStore.importUri(uri)
		val file = app.blobStore.file(blobId)
		onCapture(Captured("video", blobId, mimeHint ?: context.contentResolver.getType(uri) ?: "video/mp4", durationMs = MediaInfo.durationMs(file)))
	}

	// The camera app may kill us while it's open; keep the pending output path
	// in saveable state so the result can still be matched up.
	var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }

	fun newCameraFile(ext: String): Pair<File, Uri> {
		val dir = File(context.filesDir, "camera").apply { mkdirs() }
		val f = File(dir, "${System.currentTimeMillis()}.$ext")
		return f to FileProvider.getUriForFile(context, context.packageName + ".files", f)
	}

	val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
		val path = pendingCameraPath ?: return@rememberLauncherForActivityResult
		pendingCameraPath = null
		val f = File(path)
		if (ok && f.isFile) scope.launch {
			val blobId = app.blobStore.adoptFile(f)
			val file = app.blobStore.file(blobId)
			val dims = MediaInfo.imageDims(file)
			onCapture(Captured("photo", blobId, "image/jpeg", w = dims?.w, h = dims?.h, place = MediaInfo.gps(file)))
		} else f.delete()
	}

	val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
		val path = pendingCameraPath ?: return@rememberLauncherForActivityResult
		pendingCameraPath = null
		val f = File(path)
		if (ok && f.isFile) scope.launch {
			val blobId = app.blobStore.adoptFile(f)
			onCapture(Captured("video", blobId, "video/mp4", durationMs = MediaInfo.durationMs(app.blobStore.file(blobId))))
		} else f.delete()
	}

	val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
		scope.launch { for (u in uris) runCatching { addPhotoFromUri(u, null) }.onFailure { error = it.message } }
	}
	val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
		if (uri != null) scope.launch { runCatching { addVideoFromUri(uri, null) }.onFailure { error = it.message } }
	}

	// One row, docked at the bottom of the editor: five actions a thumb can reach
	// with the keyboard up. While recording it becomes Stop / Cancel.
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
			if (recording) {
				Button(
					onClick = { stopRecording() },
					colors = ButtonDefaults.buttonColors(containerColor = c.wax),
					modifier = Modifier.weight(1f)
				) { Text("■ Stop ${fmtDuration(elapsedMs)}") }
				OutlinedButton(onClick = { recorder.cancel(); recording = false }) { Text("Cancel") }
			} else {
				CaptureAction(Icons.Filled.Mic, "Record", "Record audio", Modifier.weight(1f)) {
					val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
					if (granted) startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
				}
				CaptureAction(Icons.Filled.PhotoCamera, "Photo", "Take a photo", Modifier.weight(1f)) {
					val (f, uri) = newCameraFile("jpg")
					pendingCameraPath = f.path
					takePicture.launch(uri)
				}
				CaptureAction(Icons.Filled.Videocam, "Video", "Record a video", Modifier.weight(1f)) {
					val (f, uri) = newCameraFile("mp4")
					pendingCameraPath = f.path
					captureVideo.launch(uri)
				}
				CaptureAction(Icons.Filled.PhotoLibrary, "Photos", "Add photos from the library", Modifier.weight(1f)) {
					pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
				}
				CaptureAction(Icons.Filled.VideoLibrary, "Videos", "Add a video from the library", Modifier.weight(1f)) {
					pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
				}
			}
		}
		error?.let { ErrorText(it) }
	}
}

/** One capture action: a line icon over a short label, 52dp tall. */
@Composable
private fun CaptureAction(icon: ImageVector, label: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
	Column(
		modifier
			.clip(RoundedCornerShape(6.dp))
			.clickable(onClickLabel = description, role = Role.Button, onClick = onClick)
			.heightIn(min = 52.dp)
			.padding(vertical = 4.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center
	) {
		Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
		Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, maxLines = 1)
	}
}
