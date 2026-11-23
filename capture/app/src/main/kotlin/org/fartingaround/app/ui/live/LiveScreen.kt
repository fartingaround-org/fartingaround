package org.fartingaround.app.ui.live

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.fartingaround.app.captureApp
import org.fartingaround.app.net.ApiClient
import org.fartingaround.app.ui.components.ErrorText
import org.fartingaround.app.ui.components.Muted
import org.fartingaround.app.ui.theme.LocalCaptureColors
import org.fartingaround.app.ui.theme.imageFilter

/** What's live on the site right now — a read-only list from /api/stories. */
@Composable
fun LiveScreen() {
	val context = LocalContext.current
	val app = context.captureApp
	var stories by remember { mutableStateOf<List<ApiClient.LiveStory>>(emptyList()) }
	var loading by remember { mutableStateOf(true) }
	var error by remember { mutableStateOf<String?>(null) }
	var reloadKey by remember { mutableIntStateOf(0) }
	var base by remember { mutableStateOf("") }

	LaunchedEffect(reloadKey) {
		loading = true; error = null
		try {
			val s = app.settings.load()
			base = s.base
			stories = app.api.liveStories(s.base)
		} catch (e: Exception) {
			error = e.message ?: "Couldn't load stories"
		}
		loading = false
	}

	Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
		Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
			Text("Live", style = MaterialTheme.typography.headlineLarge)
			TextButton(onClick = { reloadKey++ }, enabled = !loading) { Text(if (loading) "…" else "Refresh") }
		}
		error?.let { ErrorText(it) }
		if (!loading && error == null && stories.isEmpty()) Muted("No published stories yet.")

		LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
			items(stories, key = { it.slug }) { s ->
				Row(
					Modifier
						.fillMaxWidth()
						.clip(RoundedCornerShape(4.dp))
						.background(MaterialTheme.colorScheme.surfaceVariant)
						.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
						.clickable {
							val url = if (s.href.startsWith("http")) s.href else base + s.href
							context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
						}
						.padding(10.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					val cover = s.coverThumbUrl ?: s.coverUrl
					if (cover != null) {
						AsyncImage(
							model = cover, contentDescription = null, contentScale = ContentScale.Crop,
							colorFilter = imageFilter(),
							modifier = Modifier.size(72.dp).clip(RoundedCornerShape(3.dp))
						)
					}
					Column(Modifier.weight(1f)) {
						Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
						val meta = listOfNotNull(s.date, s.location?.takeIf { it.isNotBlank() }).joinToString(" · ")
						Text(meta, style = MaterialTheme.typography.bodySmall, color = LocalCaptureColors.current.pencil)
						s.summary?.takeIf { it.isNotBlank() }?.let {
							Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
						}
					}
				}
			}
		}
	}
}
