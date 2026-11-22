package fartingaround.capture.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import fartingaround.capture.model.SyncState
import fartingaround.capture.ui.theme.LocalCaptureColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The small mono-caps section label used all over the old app. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
	Text(
		text.uppercase(),
		style = MaterialTheme.typography.labelSmall,
		color = LocalCaptureColors.current.pencil,
		modifier = modifier
	)
}

/** Muted helper text. */
@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
	Text(text, style = MaterialTheme.typography.bodySmall, color = LocalCaptureColors.current.pencil, modifier = modifier)
}

/** Error text in the site's wax red. */
@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
	Text(text, style = MaterialTheme.typography.bodySmall, color = LocalCaptureColors.current.wax, modifier = modifier)
}

/** local / syncing… / synced / error pill. */
@Composable
fun SyncBadge(state: SyncState, dirty: Boolean = false) {
	val c = LocalCaptureColors.current
	val (label, bg, fg) = when {
		state == SyncState.SYNCED && !dirty -> Triple("synced", MaterialTheme.colorScheme.surfaceVariant, c.postmark) // the normal state stays quiet
		state == SyncState.SYNCING -> Triple("syncing…", c.stamp, MaterialTheme.colorScheme.onBackground)
		state == SyncState.ERROR -> Triple("error", c.wax, MaterialTheme.colorScheme.background)
		else -> Triple("local", MaterialTheme.colorScheme.surfaceVariant, c.pencil)
	}
	Text(
		label.uppercase(),
		style = MaterialTheme.typography.labelSmall,
		color = fg,
		modifier = Modifier
			.clip(RoundedCornerShape(2.dp))
			.background(bg)
			.padding(horizontal = 6.dp, vertical = 2.dp)
	)
}

private val shortFmt = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())

/** "Nov 4, 2:14 PM" in the device zone. Falls back to the raw string. */
fun fmtShort(iso: String): String = runCatching {
	shortFmt.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault(iso)

/**
 * When a feed item was published. A feed that only knows the day says noon
 * UTC (as this site does for a backdated story), which would read as a made-up
 * "6:00 AM" here: those show the day alone.
 */
fun fmtPublished(epochMillis: Long): String {
	val at = Instant.ofEpochMilli(epochMillis)
	return if (epochMillis % 86_400_000L == 43_200_000L) fmtDay(at.toString()) else fmtShort(at.toString())
}

private val dayFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

/** "Nov 4, 2026" from a story's date ("2026-11-04" or a full timestamp). Falls back to the raw string. */
fun fmtDay(date: String): String = runCatching {
	dayFmt.format(java.time.LocalDate.parse(date.take(10)))
}.getOrDefault(date)

fun fmtDuration(ms: Long?): String {
	if (ms == null || ms <= 0) return ""
	val s = Math.round(ms / 1000.0)
	return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

