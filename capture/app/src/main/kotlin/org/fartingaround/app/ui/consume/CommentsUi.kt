package org.fartingaround.app.ui.consume

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.fartingaround.app.consume.Comment
import org.fartingaround.app.consume.CommentThread
import org.fartingaround.app.consume.Story
import org.fartingaround.app.consume.Story.Block
import org.fartingaround.app.ui.components.ErrorText
import org.fartingaround.app.ui.components.Eyebrow
import org.fartingaround.app.ui.components.Muted
import org.fartingaround.app.ui.components.fmtDay
import org.fartingaround.app.ui.theme.LocalCaptureColors
import org.fartingaround.app.ui.theme.imageFilter
import kotlinx.coroutines.launch

/**
 * The comments under a story, laid out as the site does: ordinary comments in
 * order, and everything said about one photo gathered under that photo (its
 * thumbnail and caption shown once), where the first of them was posted.
 * Tapping the photo opens it in the viewer with its comments.
 *
 * [onPost] is (body, parentId, photoId). Only the site's owner can post from
 * the app (see CommentsApi); everyone else is pointed at the site.
 */
@Composable
internal fun CommentsSection(
	story: Story,
	thread: CommentThread,
	onOpenPhoto: (String) -> Unit,
	onPost: suspend (String, String?, String?) -> Unit,
	modifier: Modifier = Modifier
) {
	var replyTo by remember { mutableStateOf<Comment?>(null) }
	val entries = remember(thread) {
		val out = mutableListOf<Pair<String?, MutableList<Comment>>>()
		val groups = mutableMapOf<String, MutableList<Comment>>()
		for (c in thread.comments) {
			val photo = c.photoId
			if (photo == null) {
				out.add(Pair<String?, MutableList<Comment>>(null, mutableListOf(c)))
			} else {
				val existing = groups[photo]
				if (existing != null) {
					existing.add(c)
				} else {
					val list = mutableListOf(c)
					groups[photo] = list
					out.add(Pair<String?, MutableList<Comment>>(photo, list))
				}
			}
		}
		out
	}
	val photos = remember(story) { story.blocks.filterIsInstance<Block.Dump>().flatMap { it.photos }.associateBy { it.imageId } }

	Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
		Text(
			when (thread.total) { 0 -> "Comments"; 1 -> "1 comment"; else -> "${thread.total} comments" },
			style = MaterialTheme.typography.titleLarge, fontStyle = FontStyle.Italic
		)
		entries.forEach { entry ->
			val photo = entry.first
			if (photo != null) {
				Row(
					Modifier.fillMaxWidth().clickable { onOpenPhoto(photo) },
					verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
				) {
					AsyncImage(
						model = story.img(photo, "w=160,h=160,fit=cover"), contentDescription = photos[photo]?.alt,
						contentScale = ContentScale.Crop, colorFilter = imageFilter(),
						modifier = Modifier.size(56.dp).clip(RoundedCornerShape(2.dp))
					)
					Column {
						Eyebrow("On this photo")
						val caption = photos[photo]?.caption
						if (!caption.isNullOrBlank()) Text(caption, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
					}
				}
			}
			Column(Modifier.padding(start = if (photo != null) 20.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
				entry.second.forEach { c ->
					CommentRow(c, canReply = thread.canPost && !c.deleted, onReply = { replyTo = c })
					c.replies.forEach { r -> CommentRow(r, canReply = false, modifier = Modifier.padding(start = 16.dp)) }
				}
			}
		}
		if (thread.canPost) {
			val target = replyTo
			if (target != null) ReplyingTo(target) { replyTo = null }
			CommentComposer(label = if (target != null) "Your reply" else "Add a comment as ${thread.postAs ?: "the author"}") { text ->
				onPost(text, target?.id, null)
				replyTo = null
			}
		} else {
			Muted("To comment, open the story on the site and sign in.")
		}
	}
}

/**
 * The comments about one photo, over the lower part of the viewer.
 * [onPost] is (body, parentId): a new comment about the photo, or a reply to one.
 *
 * The caller sizes the panel and keeps it above the keyboard. The list is the
 * part that gives way: it scrolls, and it jumps to the newest comment when
 * the panel opens, a comment arrives or the keyboard changes the room there is.
 */
@Composable
internal fun PhotoCommentsPanel(
	thread: CommentThread,
	imageId: String,
	onClose: () -> Unit,
	onPost: suspend (String, String?) -> Unit,
	modifier: Modifier = Modifier
) {
	val list = thread.about(imageId)
	val count = list.sumOf { 1 + it.replies.size }
	var replyTo by remember(imageId) { mutableStateOf<Comment?>(null) }
	val scroll = rememberScrollState()
	val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
	LaunchedEffect(imageId, count, keyboard, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }

	// A Surface so text takes the theme's colour (the viewer around it is plain black)
	// and so swipes on the panel don't turn the photo underneath.
	Surface(modifier, color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
		Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
				Text("On this photo", style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic)
				IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Hide comments") }
			}
			// Takes whatever height is left, so the box below is never pushed off.
			Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
				if (list.isEmpty()) Muted("Nothing yet.")
				list.forEach { c ->
					CommentRow(c, canReply = thread.canPost && !c.deleted, onReply = { replyTo = c })
					c.replies.forEach { r -> CommentRow(r, canReply = false, modifier = Modifier.padding(start = 16.dp)) }
				}
			}
			if (thread.canPost) {
				val target = replyTo
				if (target != null) ReplyingTo(target) { replyTo = null }
				CommentComposer(label = if (target != null) "Your reply" else "Comment as ${thread.postAs ?: "the author"}") { text ->
					onPost(text, target?.id)
					replyTo = null
				}
			} else {
				Muted("To comment, open the story on the site and sign in.")
			}
		}
	}
}

/** What is being replied to, kept in sight above the text box while typing. */
@Composable
private fun ReplyingTo(c: Comment, onCancel: () -> Unit) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Column(Modifier.weight(1f)) {
			Eyebrow("Replying to ${c.name}")
			Text(
				c.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
				style = MaterialTheme.typography.bodySmall, color = LocalCaptureColors.current.pencil
			)
		}
		TextButton(onClick = onCancel) { Text("Cancel") }
	}
}

@Composable
private fun CommentRow(c: Comment, canReply: Boolean, modifier: Modifier = Modifier, onReply: () -> Unit = {}) {
	Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
		if (c.deleted) {
			Muted("[deleted]")
		} else {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
				if (c.byOwner) Eyebrow("author")
				Muted(fmtDay(c.createdAt))
			}
			Prose(c.body)
			if (canReply) {
				Text(
					"REPLY", style = MaterialTheme.typography.labelSmall, color = LocalCaptureColors.current.pencil,
					modifier = Modifier.clickable(onClick = onReply).padding(vertical = 8.dp)
				)
			}
		}
	}
}

/** A text box and a Post button. [onSend] throws to show its message under the box. */
@Composable
private fun CommentComposer(label: String, onSend: suspend (String) -> Unit) {
	val scope = rememberCoroutineScope()
	var text by remember { mutableStateOf("") }
	var sending by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		OutlinedTextField(
			value = text, onValueChange = { text = it }, label = { Text(label) },
			minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth()
		)
		val problem = error
		if (problem != null) ErrorText(problem)
		Button(
			enabled = !sending && text.isNotBlank(),
			onClick = {
				sending = true
				error = null
				scope.launch {
					try {
						onSend(text.trim())
						text = ""
					} catch (e: Exception) {
						error = e.message ?: "Couldn't post the comment."
					}
					sending = false
				}
			}
		) { Text(if (sending) "Posting…" else "Post") }
	}
}
