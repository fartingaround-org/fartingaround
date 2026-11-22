package fartingaround.capture.ui.editor

import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.common.util.UnstableApi
import android.content.Context
import androidx.compose.runtime.rememberUpdatedState
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import fartingaround.capture.ui.components.fmtDuration
import fartingaround.capture.ui.theme.LocalCaptureColors
import kotlinx.coroutines.delay
import java.io.File

/**
 * An ExoPlayer with no clip loaded yet. [headers] are sent with http(s) requests only:
 * the draft preview passes the capture token, because the site will not serve a
 * draft's audio or video to an anonymous player. Local files ignore them.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun buildMediaPlayer(context: Context, headers: Map<String, String>): ExoPlayer {
	val builder = ExoPlayer.Builder(context)
	if (headers.isNotEmpty()) {
		val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
		builder.setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context, http)))
	}
	return builder.build()
}

private fun buildPlayer(context: Context, uri: Uri, headers: Map<String, String>): ExoPlayer =
	buildMediaPlayer(context, headers).apply {
		setMediaItem(MediaItem.fromUri(uri))
		prepare()
	}

/** Compact play/pause + scrubber for a local recording, backed by ExoPlayer. */
@Composable
fun AudioPlayer(file: File) = AudioPlayer(Uri.fromFile(file))

/**
 * Same player for any URI — the reader streams audio straight from the site.
 *
 * [onProgress] reports the playback position and whether it is playing, often
 * enough to follow a transcript word by word. After the clip ends it reports
 * position 0, not playing.
 */
@Composable
fun AudioPlayer(uri: Uri, headers: Map<String, String> = emptyMap(), onProgress: ((positionMs: Long, playing: Boolean) -> Unit)? = null) {
	val progress by rememberUpdatedState(onProgress)
	val context = LocalContext.current
	val player = remember(uri, headers) { buildPlayer(context, uri, headers) }
	DisposableEffect(player) { onDispose { player.release() } }

	var isPlaying by remember { mutableStateOf(false) }
	var positionMs by remember { mutableLongStateOf(0L) }
	var durationMs by remember { mutableLongStateOf(0L) }
	var scrub by remember { mutableFloatStateOf(-1f) } // -1 = not scrubbing

	DisposableEffect(player) {
		val listener = object : Player.Listener {
			override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
			override fun onPlaybackStateChanged(state: Int) {
				if (state == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0)
				if (state == Player.STATE_ENDED) { player.seekTo(0); player.pause() }
			}
		}
		player.addListener(listener)
		onDispose { player.removeListener(listener) }
	}
	LaunchedEffect(isPlaying) {
		// Word-by-word following needs a finer tick than the scrubber does.
		val tick = if (progress != null) 50L else 200L
		while (isPlaying) { positionMs = player.currentPosition; progress?.invoke(positionMs, true); delay(tick) }
		positionMs = player.currentPosition
		progress?.invoke(positionMs, false)
	}

	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		IconButton(onClick = { if (isPlaying) player.pause() else player.play() }) {
			Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (isPlaying) "Pause" else "Play")
		}
		val shown = if (scrub >= 0) scrub else if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
		Slider(
			value = shown.coerceIn(0f, 1f),
			onValueChange = { scrub = it },
			onValueChangeFinished = {
				player.seekTo((scrub * durationMs).toLong()); positionMs = player.currentPosition; scrub = -1f
				progress?.invoke(positionMs, isPlaying)
			},
			modifier = Modifier.weight(1f)
		)
		Text(
			fmtDuration(if (scrub >= 0) (scrub * durationMs).toLong() else positionMs).ifBlank { "0:00" },
			style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
			color = LocalCaptureColors.current.pencil
		)
	}
}

/** Full-width video playback in a dialog. */
@Composable
fun VideoDialog(file: File, onDismiss: () -> Unit) = VideoDialog(Uri.fromFile(file), onDismiss = onDismiss)

@Composable
fun VideoDialog(uri: Uri, headers: Map<String, String> = emptyMap(), onDismiss: () -> Unit) {
	val context = LocalContext.current
	val exo = remember(uri, headers) { buildPlayer(context, uri, headers).apply { playWhenReady = true } }
	DisposableEffect(exo) { onDispose { exo.release() } }

	Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
		AndroidView(
			// `player` here is PlayerView's property; the ExoPlayer is deliberately named differently.
			factory = { ctx -> PlayerView(ctx).apply { player = exo; useController = true } },
			modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
		)
	}
}
