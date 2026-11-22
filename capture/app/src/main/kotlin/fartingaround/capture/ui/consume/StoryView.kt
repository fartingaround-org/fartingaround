package fartingaround.capture.ui.consume

import androidx.compose.material.icons.automirrored.filled.VolumeOff
import fartingaround.capture.ui.editor.buildMediaPlayer
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaItem
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.mutableLongStateOf
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import fartingaround.capture.captureApp
import fartingaround.capture.consume.CommentThread
import fartingaround.capture.consume.CommentsApi
import fartingaround.capture.consume.Story
import fartingaround.capture.data.CaptureSettings
import fartingaround.capture.consume.Story.Block
import fartingaround.capture.ui.components.Eyebrow
import fartingaround.capture.ui.components.Muted
import fartingaround.capture.ui.components.fmtDuration
import fartingaround.capture.ui.editor.AudioPlayer
import fartingaround.capture.ui.editor.VideoDialog
import fartingaround.capture.ui.theme.LocalCaptureColors
import fartingaround.capture.ui.theme.imageFilter
import kotlinx.coroutines.delay

/**
 * Native rendering of a `_fartingaround` story: the enhanced view a ✦ feed
 * gets instead of the HTML WebView. One LazyColumn, one row per block.
 *
 * A blank [title] (or "Untitled") makes it a note: no headline and no cover.
 */
@Composable
fun StoryView(story: Story, title: String, feedTitle: String, modifier: Modifier = Modifier) {
	// No title: a note. Only the date line stands above the content, and there is no cover.
	val note = Story.isNoteTitle(title)
	var lightbox by remember { mutableStateOf<Pair<Block.Dump, Int>?>(null) }
	/** The viewer was opened from a photo's comments: start with them showing. */
	var lightboxComments by remember { mutableStateOf(false) }

	// ── Comments ────────────────────────────────────────────────────────────
	// Read from the site each time the story is opened. On the owner's own site
	// the capture token goes along, which makes us the author there: we see
	// what the author sees and may post. Anywhere else the app only reads.
	val app = LocalContext.current.captureApp
	val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = CaptureSettings())
	val ownerToken = settings.apiToken.takeIf { it.isNotBlank() && settings.base.equals(story.base, ignoreCase = true) }
	var thread by remember(story.base, story.slug) { mutableStateOf<CommentThread?>(null) }
	var reload by remember { mutableIntStateOf(0) }
	LaunchedEffect(story.base, story.slug, ownerToken, reload) {
		// A preview of something not yet uploaded has nothing on the site to ask about.
		thread = if (story.local.isEmpty()) CommentsApi.load(app.api.http, story, ownerToken) else null
	}
	val post: suspend (String, String?, String?) -> Unit = { body, parentId, photoId ->
		val token = ownerToken ?: throw IllegalStateException("To comment, open the story on the site and sign in.")
		CommentsApi.post(app.api.http, story, token, body, parentId, photoId)
		reload++
	}
	// A photo's comments in the list: open the dump that holds that photo, on it.
	val openPhoto: (String) -> Unit = { imageId ->
		for (d in story.blocks.filterIsInstance<Block.Dump>()) {
			val i = d.photos.indexOfFirst { it.imageId == imageId }
			if (i >= 0) {
				lightboxComments = true
				lightbox = d to i
				break
			}
		}
	}

	LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 48.dp)) {
		item("head") {
			Column(Modifier.padding(horizontal = 16.dp)) {
				Eyebrow(feedTitle)
				if (!note) Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 4.dp))
				StoryMeta(story)
			}
			if (!note) story.coverImageId?.let { id ->
				AsyncImage(
					model = story.img(id, "w=1200,h=675,fit=cover,gravity=${story.coverFocus?.x ?: 0.5}x${story.coverFocus?.y ?: 0.5}"),
					contentDescription = null, contentScale = ContentScale.Crop, colorFilter = imageFilter(),
					modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(top = 12.dp)
				)
			}
		}
		items(story.blocks, key = { it.id }) { b ->
			Box(Modifier.padding(top = 20.dp)) {
				when (b) {
					is Block.Text -> Prose(b.body, Modifier.padding(horizontal = 16.dp))
					is Block.Photo -> PhotoBlock(story, b)
					is Block.Diptych -> DiptychBlock(story, b)
					is Block.Gallery -> GalleryBlock(story, b)
					is Block.Dump -> DumpBlock(story, b, countFor = { id -> thread?.countAbout(id) ?: 0 }) { lightbox = b to it }
					is Block.Pull -> Text(
						"“${b.body}”", style = MaterialTheme.typography.headlineSmall, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center,
						color = LocalCaptureColors.current.postmark, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)
					)
					is Block.Aside -> Column(
						Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(4.dp))
							.background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)).padding(12.dp)
					) {
						b.label?.let { Eyebrow(it) }
						Prose(b.body, Modifier.padding(top = if (b.label != null) 4.dp else 0.dp))
					}
					is Block.Divider -> Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
						HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
						val label = listOfNotNull(b.place, b.date).joinToString(" · ")
						if (label.isNotBlank()) Eyebrow(label, Modifier.padding(top = 8.dp))
					}
					is Block.Audio -> AudioBlock(story, b)
					is Block.Video -> VideoBlock(story, b)
				}
			}
		}
		val t = thread
		if (t != null && t.enabled && (t.comments.isNotEmpty() || t.canPost)) {
			item("story-comments") {
				CommentsSection(story, t, onOpenPhoto = openPhoto, onPost = post, modifier = Modifier.padding(top = 28.dp))
			}
		}
	}

	lightbox?.let { (dump, start) ->
		Lightbox(story, dump, start, thread, lightboxComments, post) { lightbox = null; lightboxComments = false }
	}
}

/** Whether [NoteLead] has something to show for this story. */
internal fun Story.hasInlineLead(): Boolean = when (val b = lead) {
	is Block.Photo, is Block.Audio, is Block.Video, is Block.Text, is Block.Pull, is Block.Aside -> true
	is Block.Dump -> b.photos.isNotEmpty()
	else -> false
}

/**
 * A note's first block, shown where the note is listed (the Consume timeline)
 * instead of a row to tap through. Photos and text open the note; audio and
 * video play in place.
 */
@Composable
internal fun NoteLead(story: Story, onOpen: () -> Unit) {
	when (val b = story.lead) {
		is Block.Photo -> Box(Modifier.clickable(onClick = onOpen)) { PhotoBlock(story, b) }
		is Block.Audio -> AudioBlock(story, b)
		is Block.Video -> VideoBlock(story, b)
		// A long text is cut short here; opening the note shows the rest.
		is Block.Text -> Box(Modifier.heightIn(max = 220.dp).clipToBounds().clickable(onClick = onOpen)) { Prose(b.body, Modifier.padding(horizontal = 16.dp)) }
		is Block.Pull -> Box(Modifier.clickable(onClick = onOpen)) { Prose(b.body, Modifier.padding(horizontal = 16.dp)) }
		is Block.Aside -> Box(Modifier.clickable(onClick = onOpen)) { Prose(b.body, Modifier.padding(horizontal = 16.dp)) }
		is Block.Dump -> b.photos.firstOrNull()?.let { p ->
			Column(Modifier.clickable(onClick = onOpen)) {
				AsyncImage(
					model = story.img(p.imageId, "w=1200,q=84"), contentDescription = p.alt, contentScale = ContentScale.Crop, colorFilter = imageFilter(),
					modifier = Modifier.fillMaxWidth().aspectRatio(1.5f)
				)
				Caption(listOfNotNull(b.caption ?: p.caption, "${b.photos.size} photos").joinToString(" · "))
			}
		}
		else -> {}
	}
}

@Composable
private fun StoryMeta(story: Story) {
	val bits = buildList {
		add(story.date)
		story.location?.let { add(it) }
		story.weather?.let { w -> listOfNotNull(w.tempF?.let { "${it.toInt()}°F" }, w.conditions).joinToString(" ").takeIf { it.isNotBlank() }?.let { add(it) } }
		story.astro?.moonPhaseLabel?.let { add(it) }
	}
	Muted(bits.joinToString(" · "), Modifier.padding(top = 4.dp))
}

@Composable
private fun Caption(text: String?) {
	if (text.isNullOrBlank()) return
	Muted(text, Modifier.padding(horizontal = 16.dp).padding(top = 6.dp))
}

/** Plain prose: blank line = paragraph; `[text](url)` and bare URLs become links. */
@Composable
fun Prose(body: String, modifier: Modifier = Modifier) {
	val link = LocalCaptureColors.current.wax
	val styles = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
	val md = Regex("""\[([^\]]+)\]\((https?://[^)\s]+)\)|(https?://[^\s<>"']+[^\s<>"'.,;:!?)])""")
	Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
		body.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { para ->
			val text = buildAnnotatedString {
				var i = 0
				for (m in md.findAll(para)) {
					append(para.substring(i, m.range.first))
					val (label, url) = if (m.groups[2] != null) m.groupValues[1] to m.groupValues[2] else m.groupValues[3] to m.groupValues[3]
					withLink(LinkAnnotation.Url(url, styles)) { append(label) }
					i = m.range.last + 1
				}
				append(para.substring(i))
			}
			Text(text, style = MaterialTheme.typography.bodyLarge)
		}
	}
}

@Composable
private fun PhotoBlock(story: Story, b: Block.Photo) {
	val pad = if (b.size == "full") 0.dp else 16.dp
	// Same framing as the site: the photo fills a frame of the block's aspect
	// (4/5 when unset) and is cropped to it. Fitting it instead left empty
	// bands above and below whenever the photo's shape differed.
	Column {
		AsyncImage(
			model = story.img(b.imageId, "w=1200,q=84"), contentDescription = b.alt, contentScale = ContentScale.Crop, colorFilter = imageFilter(),
			modifier = Modifier.fillMaxWidth().padding(horizontal = pad).aspectRatio(b.aspect ?: 0.8f)
		)
		Caption(b.caption)
	}
}

@Composable
private fun DiptychBlock(story: Story, b: Block.Diptych) {
	Column {
		Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			for (p in listOf(b.left, b.right)) {
				val url = p.imageId?.let { story.img(it, "w=720,q=82") } ?: p.r2?.let { story.r2(it, 720) }
				AsyncImage(model = url, contentDescription = p.alt, contentScale = ContentScale.Crop, colorFilter = imageFilter(), modifier = Modifier.weight(1f).aspectRatio(b.aspect ?: 0.8f))
			}
		}
		Caption(b.caption ?: listOfNotNull(b.left.caption, b.right.caption).joinToString(" / ").ifBlank { null })
	}
}

/** Astrophotography gallery: pager, with annotation / starless layers switchable per photo. */
@Composable
private fun GalleryBlock(story: Story, b: Block.Gallery) {
	if (b.items.isEmpty()) return
	val pager = rememberPagerState { b.items.size }
	Column {
		// "full" galleries run edge to edge, as on the site.
		val pad = if (b.size == "full") 0.dp else 16.dp
		HorizontalPager(pager, pageSpacing = 8.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = pad)) { i ->
			val p = b.items[i]
			var layer by remember(p) { mutableStateOf("photo") }
			val id = when (layer) { "annotated" -> p.annotationId; "starless" -> p.starlessId; else -> null } ?: p.imageId
			val url = id?.let { story.img(it, "w=1600,q=86,fit=scale-down") } ?: p.r2?.let { story.r2(it, 1600) }
			Column {
				Box(Modifier.fillMaxWidth().aspectRatio(b.aspect ?: 1.5f).background(Color.Black)) {
					// Cropped to the frame like the site; the overlay is cropped the same way so it stays registered.
					AsyncImage(model = url, contentDescription = p.alt, contentScale = ContentScale.Crop, colorFilter = imageFilter(), modifier = Modifier.fillMaxSize())
					if (layer == "photo" && p.overlayId != null) {
						AsyncImage(model = story.img(p.overlayId, "w=1600,q=86,fit=scale-down,format=auto"), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
					}
				}
				if (p.annotationId != null || p.starlessId != null) {
					Row(Modifier.padding(horizontal = 16.dp - pad).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						FilterChip(layer == "photo", { layer = "photo" }, { Text("Photo") })
						if (p.annotationId != null) FilterChip(layer == "annotated", { layer = "annotated" }, { Text("Labelled") })
						if (p.starlessId != null) FilterChip(layer == "starless", { layer = "starless" }, { Text("Starless") })
					}
				}
				p.caption?.let { Muted(it, Modifier.padding(horizontal = 16.dp - pad).padding(top = 6.dp)) }
			}
		}
		if (b.items.size > 1) Muted("${pager.currentPage + 1} / ${b.items.size}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
		Caption(b.caption)
	}
}

/** Photo dump: rows of square-ish thumbnails, then the map when photos carry coords. */
@Composable
private fun DumpBlock(story: Story, b: Block.Dump, countFor: (String) -> Int = { 0 }, onOpen: (Int) -> Unit) {
	val per = b.rowSize.coerceIn(2, 5)
	// A photo with a section title starts a new group; `start` keeps the lightbox
	// index counting across the whole dump.
	val groups = remember(b) {
		val out = mutableListOf<Triple<String?, Int, MutableList<Story.DumpPhoto>>>()
		b.photos.forEachIndexed { i, p ->
			if (p.section != null || out.isEmpty()) out.add(Triple(p.section, i, mutableListOf()))
			out.last().third.add(p)
		}
		out
	}
	Column {
		groups.forEachIndexed { g, (title, start, photos) ->
			if (title != null) {
				Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp).padding(top = if (g == 0) 0.dp else 16.dp, bottom = 8.dp))
			}
			photos.chunked(per).forEachIndexed { r, row ->
				Row(Modifier.padding(horizontal = 16.dp).padding(top = if (r == 0) 0.dp else 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					row.forEachIndexed { i, p ->
						val index = start + r * per + i
						Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(2.dp)).clickable { onOpen(index) }) {
							AsyncImage(
								model = story.img(p.imageId, "w=480,h=480,fit=cover,q=78"), contentDescription = p.alt, contentScale = ContentScale.Crop, colorFilter = imageFilter(),
								modifier = Modifier.fillMaxSize()
							)
							// How many comments this photo has.
							val n = countFor(p.imageId)
							if (n > 0) {
								Text(
									"$n", color = Color.White, style = MaterialTheme.typography.labelSmall,
									modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp)
								)
							}
						}
					}
					repeat(per - row.size) { Box(Modifier.weight(1f)) }
				}
			}
		}
		Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
			Muted("${b.photos.size} photos", Modifier.weight(1f))
			Text("▶ Slideshow", color = LocalCaptureColors.current.wax, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { onOpen(-1) })
		}
		Caption(b.caption)
		val located = b.photos.count { it.coords != null }
		if (b.map != "none" && located > 0) {
			DumpMap(story, b, Modifier.fillMaxWidth().height(260.dp).padding(horizontal = 16.dp).padding(top = 8.dp), onOpen)
		}
	}
}

/**
 * Fullscreen pager over a dump; `start = -1` begins a slideshow from the first photo.
 *
 * Spoken captions follow the site's rules (specs/dump-audio-captions.md): outside
 * a slideshow the speaker button plays the current photo's clip, and while a
 * clip is running it mutes or unmutes without stopping the words; in a slideshow
 * the clip plays first and the countdown to the next photo starts when it ends.
 * With word timings, the words light up as they are spoken, then the ordinary
 * caption returns.
 */
@Composable
private fun Lightbox(
	story: Story,
	dump: Block.Dump,
	start: Int,
	thread: CommentThread?,
	startWithComments: Boolean,
	onPost: suspend (String, String?, String?) -> Unit,
	onClose: () -> Unit
) {
	val context = LocalContext.current
	/** The comments about the photo on screen, over the lower part of the viewer. */
	var panel by remember { mutableStateOf(startWithComments) }
	val pager = rememberPagerState(initialPage = start.coerceAtLeast(0)) { dump.photos.size }
	var playing by remember { mutableStateOf(start < 0) }
	val current = pager.settledPage
	val audio = dump.photos.getOrNull(current)?.audio

	// One player for the whole viewer; its clip is swapped as the photo changes.
	val player = remember { buildMediaPlayer(context, story.mediaHeaders) }
	/** The spoken caption has finished, or there isn't one: the countdown may run. */
	var audioDone by remember { mutableStateOf(true) }
	/** The clip has started for this photo and hasn't ended (paused counts). */
	var audioActive by remember { mutableStateOf(false) }
	var narrating by remember { mutableStateOf(false) }
	var positionMs by remember { mutableLongStateOf(0L) }
	/** Sound off, clip still running: the words keep following and the slideshow keeps its timing. Stays set across photos. */
	var muted by remember { mutableStateOf(false) }

	DisposableEffect(player) {
		val listener = object : Player.Listener {
			override fun onIsPlayingChanged(isPlaying: Boolean) { narrating = isPlaying }
			override fun onPlaybackStateChanged(state: Int) {
				if (state == Player.STATE_ENDED) { audioActive = false; audioDone = true }
			}
			// A clip that won't load must not stall the slideshow.
			override fun onPlayerError(error: PlaybackException) { audioActive = false; audioDone = true }
		}
		player.addListener(listener)
		onDispose { player.removeListener(listener); player.release() }
	}

	// Per photo: silence the previous clip, load this one, and in a slideshow start speaking.
	LaunchedEffect(current) {
		player.stop()
		player.clearMediaItems()
		positionMs = 0
		audioActive = false
		val a = dump.photos.getOrNull(current)?.audio
		if (a == null) {
			audioDone = true
		} else {
			player.setMediaItem(MediaItem.fromUri(Uri.parse(story.audio(a.id))))
			player.prepare()
			if (playing) { audioDone = false; audioActive = true; player.play() } else audioDone = true
		}
	}
	LaunchedEffect(narrating) {
		while (narrating) { positionMs = player.currentPosition; delay(50) }
	}
	// The countdown: only once any spoken caption is done. Restarts with each photo.
	LaunchedEffect(playing, current, audioDone) {
		if (!playing || !audioDone) return@LaunchedEffect
		delay(dump.slideshowSeconds.coerceAtLeast(1) * 1000L)
		val next = current + 1
		if (next >= dump.photos.size) playing = false else pager.animateScrollToPage(next)
	}

	fun toggleSlideshow() {
		playing = !playing
		// A slideshow is for watching: the comments step aside while it runs.
		if (playing) panel = false
		// Mid-caption: the voice pauses and resumes with the slideshow.
		if (audio != null && !audioDone) { if (playing) player.play() else player.pause() }
	}
	// The speaker button. While a clip is running it only mutes or unmutes, so the
	// transcript carries on; otherwise it starts the clip, with sound.
	fun toggleAudio() {
		if (audio == null) return
		if (narrating) {
			muted = !muted
			player.volume = if (muted) 0f else 1f
		} else {
			if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
			muted = false
			player.volume = 1f
			audioActive = true
			player.play()
		}
	}

	// The viewer lays itself out around the keyboard and the system bars
	// (decorFitsSystemWindows = false), and the window is told to resize, not
	// pan. Left to its default the window ALSO slid itself up to keep the text
	// box in view, on top of our own keyboard padding, which threw the comments
	// panel off the top of the screen.
	Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
		val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
		SideEffect { dialogWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
		Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
			HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
				val p = dump.photos[i]
				Box(Modifier.fillMaxSize().clickable { if (playing) toggleSlideshow() }) {
					AsyncImage(model = story.img(p.imageId, "w=2000,q=88,fit=scale-down"), contentDescription = p.alt, contentScale = ContentScale.Fit, colorFilter = imageFilter(), modifier = Modifier.fillMaxSize())
					val captionModifier = Modifier.align(Alignment.BottomStart).padding(16.dp).padding(bottom = 32.dp)
					val words = p.audio?.words.orEmpty()
					if (i == current && audioActive && words.isNotEmpty()) {
						val now = positionMs / 1000.0
						val text = buildAnnotatedString {
							for (w in words) {
								val style = when {
									now >= w.s && now < w.e -> SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)
									now >= w.e -> SpanStyle(color = Color.White.copy(alpha = 0.85f))
									else -> SpanStyle(color = Color.White.copy(alpha = 0.4f))
								}
								withStyle(style) { append(w.t) }
								append(" ")
							}
						}
						Text(text, style = MaterialTheme.typography.bodyLarge, modifier = captionModifier)
					} else {
						// The written caption, or the transcript when there isn't one.
						(p.caption ?: p.audio?.transcript)?.takeIf { it.isNotBlank() }?.let {
							Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = captionModifier)
						}
					}
				}
			}
			Row(Modifier.fillMaxWidth().align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
				Text("${pager.currentPage + 1} / ${dump.photos.size}", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp))
				Row {
					val shownId = dump.photos.getOrNull(current)?.imageId
					if (thread != null && thread.enabled && !playing && shownId != null && (thread.canPost || thread.countAbout(shownId) > 0)) {
						val n = thread.countAbout(shownId)
						TextButton(onClick = { panel = !panel }) { Text(if (n > 0) "Comments · $n" else "Comment", color = Color.White) }
					}
					if (audio != null) {
						IconButton(onClick = { toggleAudio() }) {
							Icon(
								if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
								if (!narrating) "Play audio caption" else if (muted) "Unmute audio caption" else "Mute audio caption",
								tint = if (narrating && !muted) LocalCaptureColors.current.wax else Color.White
							)
						}
					}
					IconButton(onClick = { toggleSlideshow() }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause slideshow" else "Play slideshow", tint = Color.White) }
					IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
				}
			}
			val panelId = dump.photos.getOrNull(current)?.imageId
			if (panel && thread != null && panelId != null) {
				// The panel sits above the keyboard (imePadding first), and while
				// the keyboard is up it takes nearly all the room that is left, so
				// the earlier comments stay readable while typing.
				val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
				PhotoCommentsPanel(
					thread, panelId,
					onClose = { panel = false },
					onPost = { body, parentId -> onPost(body, parentId, panelId) },
					modifier = Modifier.align(Alignment.BottomCenter).imePadding().fillMaxWidth().fillMaxHeight(if (keyboard) 0.92f else 0.6f)
				)
			}
		}
	}
}

@Composable
private fun VideoBlock(story: Story, b: Block.Video) {
	var open by remember { mutableStateOf(false) }
	Column {
		Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(b.aspect ?: (16f / 9f)).clip(RoundedCornerShape(3.dp)).background(Color.Black).clickable { open = true }) {
			b.posterImageId?.let { AsyncImage(model = story.img(it, "w=1200,q=82"), contentDescription = null, contentScale = ContentScale.Crop, colorFilter = imageFilter(), modifier = Modifier.fillMaxSize()) }
			Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(56.dp))
		}
		Caption(listOfNotNull(b.caption, fmtDuration(b.durationMs).takeIf { it.isNotBlank() }).joinToString(" · "))
	}
	if (open) VideoDialog(Uri.parse(story.video(b.id)), headers = story.mediaHeaders) { open = false }
}

/** Audio with its transcript shown as text; with word timings, the word being spoken is bold. */
@Composable
private fun AudioBlock(story: Story, b: Block.Audio) {
	var positionMs by remember(b.id) { mutableLongStateOf(0L) }
	var playing by remember(b.id) { mutableStateOf(false) }
	Column(Modifier.padding(horizontal = 16.dp)) {
		AudioPlayer(Uri.parse(story.audio(b.id)), headers = story.mediaHeaders) { pos, isPlaying -> positionMs = pos; playing = isPlaying }
		Caption(listOfNotNull(b.caption, fmtDuration(b.durationMs).takeIf { it.isNotBlank() }).joinToString(" · "))
		val scheme = MaterialTheme.colorScheme
		if (b.words.isNotEmpty()) {
			// Only while a play-through is under way; before and after it reads as plain text.
			val following = playing || positionMs > 0
			val now = positionMs / 1000.0
			val quiet = LocalCaptureColors.current.pencil
			val text = buildAnnotatedString {
				for (w in b.words) {
					val style = when {
						!following -> SpanStyle(color = scheme.onSurfaceVariant)
						now >= w.s && now < w.e -> SpanStyle(color = scheme.onBackground, fontWeight = FontWeight.Bold)
						now >= w.e -> SpanStyle(color = scheme.onSurfaceVariant)
						else -> SpanStyle(color = quiet)
					}
					withStyle(style) { append(w.t) }
					append(" ")
				}
			}
			Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
		} else {
			b.transcript?.takeIf { it.isNotBlank() }?.let {
				Text(it, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
			}
		}
	}
}
