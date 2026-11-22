package fartingaround.capture.capture

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Audio recording with the platform MediaRecorder, writing AAC in an MP4
 * container (.m4a). That's what every Android phone encodes natively, it's
 * small, and ElevenLabs Scribe accepts it — an upgrade over the WebView's
 * webm/opus, which some players choke on.
 *
 * Lifecycle: start() → (recording) → stop() returns the file, or cancel().
 * RECORD_AUDIO permission must already be granted (the UI asks first).
 */
class AudioRecorder(private val context: Context) {
	data class Result(val file: File, val mimeType: String, val durationMs: Long)

	private var recorder: MediaRecorder? = null
	private var outFile: File? = null
	private var startedAt = 0L

	val isRecording: Boolean get() = recorder != null

	fun start() {
		check(recorder == null) { "Already recording" }
		val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
		val file = File(dir, "rec-${System.currentTimeMillis()}.m4a")
		val r = MediaRecorder(context).apply {
			setAudioSource(MediaRecorder.AudioSource.MIC)
			setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
			setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
			setAudioChannels(1)
			setAudioSamplingRate(44_100)
			setAudioEncodingBitRate(128_000)
			setOutputFile(file.path)
			prepare()
			start()
		}
		recorder = r
		outFile = file
		startedAt = System.currentTimeMillis()
	}

	fun stop(): Result {
		val r = recorder ?: error("Not recording")
		val file = outFile ?: error("No output file")
		try {
			r.stop()
		} finally {
			r.release()
			recorder = null
			outFile = null
		}
		return Result(file, "audio/mp4", System.currentTimeMillis() - startedAt)
	}

	fun cancel() {
		recorder?.runCatching { stop() }
		recorder?.release()
		recorder = null
		outFile?.delete()
		outFile = null
	}
}
