package org.fartingaround.app.ui.editor

import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.fartingaround.app.capture.Astro
import org.fartingaround.app.capture.Location
import org.fartingaround.app.capture.Weather
import org.fartingaround.app.captureApp
import org.fartingaround.app.data.CaptureSettings
import org.fartingaround.app.model.AstroSnapshot
import org.fartingaround.app.model.Coords
import org.fartingaround.app.model.PendingMedia
import org.fartingaround.app.model.StoryDoc
import org.fartingaround.app.model.SyncState
import org.fartingaround.app.model.WeatherSnapshot
import org.fartingaround.app.model.addPhotos
import org.fartingaround.app.model.appendBlock
import org.fartingaround.app.model.blocks
import org.fartingaround.app.model.dbl
import org.fartingaround.app.model.moveBlock
import org.fartingaround.app.model.newBlockId
import org.fartingaround.app.model.notes
import org.fartingaround.app.model.obj
import org.fartingaround.app.model.photoLocation
import org.fartingaround.app.model.placeholders
import org.fartingaround.app.model.removeBlock
import org.fartingaround.app.model.str
import org.fartingaround.app.model.strs
import org.fartingaround.app.model.typed
import org.fartingaround.app.model.with
import org.fartingaround.app.model.withNotes
import org.fartingaround.app.model.withStrings
import org.fartingaround.app.model.withTyped
import org.fartingaround.app.ui.components.ErrorText
import org.fartingaround.app.ui.components.Eyebrow
import org.fartingaround.app.ui.components.Muted
import org.fartingaround.app.ui.components.rememberSyncLauncher
import org.fartingaround.app.ui.theme.LocalCaptureColors
import org.fartingaround.app.ui.theme.imageFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Edit one story — a draft or a published one — from rough notes to publish.
 * The document is the same JSON the web editor saves; this screen covers the
 * phone-sized subset (text, photos, dumps with spoken captions, audio, video,
 * story fields, publish). Anything else shows as a block you can reorder or
 * remove but edit on the site.
 *
 * Text fields autosave with a short debounce; everything else writes through
 * immediately. The store is a StateFlow, so sync progress shows up live.
 */
@Composable
fun StoryEditorScreen(localId: String, onClose: () -> Unit, onPreview: () -> Unit = {}) {
	val context = LocalContext.current
	val app = context.captureApp
	val scope = rememberCoroutineScope()
	val c = LocalCaptureColors.current
	val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = CaptureSettings())

	val story by remember(localId) { app.storyStore.stories.map { it[localId] } }.collectAsStateWithLifecycle(initialValue = null)
	var loaded by remember(localId) { mutableStateOf(false) }
	LaunchedEffect(localId) {
		app.storyStore.ensureLoaded()
		if (app.storyStore.get(localId) == null) onClose()
		loaded = true
	}
	val s = story
	if (!loaded || s == null) {
		Muted("Loading…", Modifier.padding(16.dp))
		return
	}
	val doc = s.doc

	/** A hand edit to the document. */
	fun edit(transform: (JsonObject) -> JsonObject) {
		scope.launch { app.storyStore.edit(localId, transform) }
	}

	/** Bytes just captured: register them for upload and reference them from the document. */
	fun addCapture(cap: Captured) {
		scope.launch {
			app.storyStore.update(localId) { cur ->
				val id = newBlockId(cap.kind)
				val ph = PendingMedia.placeholderFor(id)
				val next = when (cap.kind) {
					"photo" -> cur.doc.addPhotos(listOf(with(StoryDoc) { dumpPhoto(ph, cap.w, cap.h).takenAt(cap.place) }))
					"audio" -> cur.doc.appendBlock(StoryDoc.audioBlock(id, ph, cap.mimeType, cap.durationMs))
					else -> cur.doc.appendBlock(StoryDoc.videoBlock(id, ph, cap.mimeType, cap.durationMs))
				}
				cur.copy(doc = next, pending = cur.pending + PendingMedia(id, cap.kind, cap.blobId, cap.mimeType, cap.durationMs), docSync = SyncState.LOCAL, syncError = null)
			}
		}
	}

	fun addPending(p: PendingMedia) {
		scope.launch { app.storyStore.update(localId) { cur -> cur.copy(pending = cur.pending + p, docSync = SyncState.LOCAL) } }
	}

	// ── Cover photo ─────────────────────────────────────────────────────────
	var coverError by remember { mutableStateOf<String?>(null) }

	/**
	 * Point the story's cover at [ref] (an image id, or a `pending:` placeholder
	 * for a photo not uploaded yet), or clear it with null. [add] registers the
	 * bytes of a cover picked from the library. A previous cover that only ever
	 * lived on this phone and that no block uses is dropped with its bytes.
	 */
	fun setCover(ref: String?, add: PendingMedia? = null) {
		scope.launch {
			val cur = app.storyStore.get(localId) ?: return@launch
			val old = cur.doc.str("coverImageId")
			fun next(d: JsonObject): JsonObject {
				// The focal point belonged to the old picture.
				val bare = JsonObject(d - "coverImageId" - "coverFocus")
				return if (ref == null) bare else bare.with("coverImageId", ref)
			}
			val stillUsed = next(cur.doc).placeholders()
			val dropped = cur.pending.filter { it.placeholder == old && it.placeholder !in stillUsed }
			app.storyStore.update(localId) { c2 ->
				c2.copy(doc = next(c2.doc), pending = c2.pending.filterNot { p -> dropped.any { it.id == p.id } } + listOfNotNull(add), docSync = SyncState.LOCAL, syncError = null)
			}
			dropped.forEach { app.blobStore.delete(it.blobId) }
		}
	}

	val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
		if (uri != null) scope.launch {
			coverError = null
			runCatching {
				val blobId = app.blobStore.importUri(uri)
				val id = newBlockId("photo")
				setCover(PendingMedia.placeholderFor(id), PendingMedia(id, "photo", blobId, context.contentResolver.getType(uri) ?: "image/jpeg"))
			}.onFailure { coverError = it.message ?: "Couldn't add that photo" }
		}
	}

	// ── GPS + context ───────────────────────────────────────────────────────
	var locating by remember { mutableStateOf(false) }
	var locationError by remember { mutableStateOf<String?>(null) }

	/** Where to get the coordinates from: the phone's GPS, or (given [known]) a photo in the story. */
	fun grabLocation(known: Coords? = null) {
		scope.launch {
			locating = true
			locationError = null
			try {
				val coords = known ?: Location.current(context)
				val at = dateInstant(doc.str("date"))
				// Astronomy is pure math — commit it with the coords right away.
				edit { it.withTyped("coords", coords).withTyped("astro", Astro.compute(coords, at)) }
				// The weather is the weather now: right for where you are standing, not for a photo from another day.
				val weather = async { if (known == null) Weather.fetch(app.api.http, coords) else null }
				val name = async { if ((doc.str("location") ?: "").isBlank()) Location.reverseGeocode(app.api.http, coords) else null }
				weather.await()?.let { w -> edit { it.withTyped("weather", w) } }
				name.await()?.let { n -> edit { d -> if ((d.str("location") ?: "").isBlank()) d.with("location", n) else d } }
			} catch (e: Exception) {
				locationError = e.message ?: "Couldn't get location"
			} finally {
				locating = false
			}
		}
	}
	val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if (granted) grabLocation() else locationError = "Location permission denied."
	}

	// ── Sync / publish / delete ─────────────────────────────────────────────
	val sync = rememberSyncLauncher()
	var confirmDelete by remember { mutableStateOf(false) }
	var confirmPublic by remember { mutableStateOf(false) }
	var pendingRemove by remember { mutableStateOf<String?>(null) }
	var resolving by remember { mutableStateOf(false) }

	// The body scrolls; the capture bar stays docked under it, above the keyboard.
	Column(Modifier.fillMaxSize()) {
		Column(
			Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp)
		) {
			Spacer(Modifier.height(4.dp))
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
				TextButton(onClick = onClose) { Text("← Stories", style = MaterialTheme.typography.labelMedium) }
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					TextButton(onClick = onPreview) { Text("Preview", style = MaterialTheme.typography.labelMedium) }
					TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = c.wax) }
					Button(onClick = { sync(listOf(localId)) }, enabled = s.docSync != SyncState.SYNCING && s.conflict == null) {
						Text(if (s.docSync == SyncState.SYNCING) "Syncing…" else if (s.isDirty) "Sync" else "Synced ✓")
					}
				}
			}
			s.syncError?.takeIf { s.conflict == null }?.let { ErrorText(it) }

			// A 409 from the site: the same story was saved there since this copy was made.
			s.conflict?.let { server ->
				Column(
					Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
						.border(1.dp, c.wax, RoundedCornerShape(4.dp)).padding(12.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp)
				) {
					Eyebrow("Changed on the site")
					Text("This story was saved on the site (${server.str("updatedAt")?.take(16)?.replace('T', ' ') ?: "later"}) after this phone's copy. Which version wins?", style = MaterialTheme.typography.bodyMedium)
					if (s.pending.isNotEmpty()) Muted("Taking the site's version drops ${s.pending.size} unsent media file(s) from this phone.")
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						Button(enabled = !resolving, onClick = { scope.launch { resolving = true; runCatching { app.syncEngine.resolveConflict(localId, keepMine = true) }; resolving = false } }) { Text("Keep mine") }
						OutlinedButton(enabled = !resolving, onClick = { scope.launch { resolving = true; runCatching { app.syncEngine.resolveConflict(localId, keepMine = false) }; resolving = false } }) { Text("Take the site's") }
					}
				}
			}

			DebouncedField(key = "$localId-title", value = doc.str("title") ?: "", label = "Title", placeholder = "What is this about?", modifier = Modifier.fillMaxWidth(),
				onCommit = { v -> edit { it.with("title", v) } })
			DebouncedField(key = "$localId-notes", value = doc.notes, label = "Notes", placeholder = "Write whatever. The point is to capture, not polish.", singleLine = false, minLines = 4, modifier = Modifier.fillMaxWidth(),
				onCommit = { v -> edit { it.withNotes(v) } })

			DateTimeField(
				label = "When",
				iso = dateInstant(doc.str("date")).toString(),
				onChange = { iso -> edit { it.with("date", isoToDate(iso)) } }
			)

			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
				DebouncedField(key = "$localId-place", value = doc.str("location") ?: "", label = "Place", placeholder = "Town, country, or vibe", modifier = Modifier.weight(1f),
					onCommit = { v -> edit { it.with("location", v) } })
				OutlinedButton(
					enabled = !locating,
					onClick = {
						val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
						if (granted) grabLocation() else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
					}
				) {
					if (locating) Text("…") else {
						Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
						Spacer(Modifier.width(6.dp))
						Text("GPS")
					}
				}
			}
			locationError?.let { ErrorText(it) }

			// A photo in the story knows where it was taken and the story has no
			// location yet: offer it. Fills the place name too when that is empty.
			val photoPlace = remember(doc) { if (doc["coords"] == null) doc.photoLocation() else null }
			if (photoPlace != null) {
				TextButton(enabled = !locating, contentPadding = PaddingValues(0.dp), onClick = { grabLocation(photoPlace) }) {
					Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
					Spacer(Modifier.width(6.dp))
					Text("Use the photo's location")
				}
			}

			doc.typed<Coords>("coords")?.let { co ->
				val acc = co.accuracy?.let { " ± ${it.roundToInt()} m" } ?: ""
				TextButton(
					contentPadding = PaddingValues(0.dp),
					onClick = {
						val url = "https://www.openstreetmap.org/?mlat=${co.lat}&mlon=${co.lon}#map=15/${co.lat}/${co.lon}"
						context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
					}
				) { Muted("%.4f, %.4f%s".format(co.lat, co.lon, acc)) }
			}
			val weather = doc.typed<WeatherSnapshot>("weather")
			val astro = doc.typed<AstroSnapshot>("astro")
			if (weather != null || astro != null) {
				Muted(listOfNotNull(
					weather?.let { "${it.tempF}°F · ${it.conditions}" },
					astro?.let { "${it.moonPhaseLabel} (${(it.moonIllumination * 100).roundToInt()}%)" }
				).joinToString(" · "))
			}

			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Eyebrow("Tags")
				TagInput(tags = doc.strs("tags"), onChange = { tags -> edit { it.withStrings("tags", tags) } })
			}

			// The picture at the top of the story and on its card in lists. Pick one
			// here, or use a photo already in the story ("Use as cover photo" on it).
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Eyebrow("Cover photo")
				val coverId = doc.str("coverImageId")?.takeIf { it.isNotBlank() }
				// Focal point: the part of the photo the crop keeps in view (0..1 each way, centre by default).
				val fx = (doc.obj("coverFocus")?.dbl("x") ?: 0.5).coerceIn(0.0, 1.0).toFloat()
				val fy = (doc.obj("coverFocus")?.dbl("y") ?: 0.5).coerceIn(0.0, 1.0).toFloat()
				var adjusting by remember(coverId) { mutableStateOf(false) }
				// The photo's own shape, learned when it loads, so a tap maps straight onto the picture.
				var aspect by remember(coverId) { mutableStateOf(3f / 2f) }
				if (coverId != null && adjusting) {
					AsyncImage(
						model = imageModel(s, coverId, settings.base, 720, app.blobStore),
						contentDescription = "Cover photo, whole picture. Tap to set the focal point.",
						contentScale = ContentScale.FillBounds, colorFilter = imageFilter(),
						onSuccess = { st ->
							val sz = st.painter.intrinsicSize
							if (sz.width > 0f && sz.height > 0f) aspect = sz.width / sz.height
						},
						modifier = Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(2.dp))
							.pointerInput(coverId) {
								detectTapGestures { at ->
									val x = (at.x / size.width).coerceIn(0f, 1f)
									val y = (at.y / size.height).coerceIn(0f, 1f)
									// Three decimals is plenty and keeps the document tidy.
									edit { it.with("coverFocus", JsonObject(mapOf("x" to JsonPrimitive((x * 1000).roundToInt() / 1000.0), "y" to JsonPrimitive((y * 1000).roundToInt() / 1000.0)))) }
								}
							}
							.drawWithContent {
								drawContent()
								val at = Offset(size.width * fx, size.height * fy)
								drawCircle(Color.Black, radius = 15.dp.toPx(), center = at, style = Stroke(4.dp.toPx()))
								drawCircle(Color.White, radius = 15.dp.toPx(), center = at, style = Stroke(2.dp.toPx()))
								drawCircle(Color.White, radius = 2.dp.toPx(), center = at)
							}
					)
					Muted("Tap the part of the photo that should stay in view when it is cropped.")
				} else if (coverId != null) {
					// As it will be cropped, leaning toward the focal point.
					AsyncImage(
						model = imageModel(s, coverId, settings.base, 720, app.blobStore),
						contentDescription = "Cover photo", contentScale = ContentScale.Crop, colorFilter = imageFilter(),
						alignment = BiasAlignment(fx * 2f - 1f, fy * 2f - 1f),
						modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(2.dp))
					)
				}
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
					OutlinedButton(onClick = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
						Text(if (coverId == null) "Add a cover photo" else "Change")
					}
					if (coverId != null) TextButton(onClick = { adjusting = !adjusting }) { Text(if (adjusting) "Done" else "Focal point") }
					if (coverId != null) TextButton(onClick = { setCover(null) }) { Text("Remove", color = c.wax) }
				}
				val title = (doc.str("title") ?: "").trim()
				when {
					coverId != null && (title.isEmpty() || title.equals("Untitled", ignoreCase = true)) ->
						Muted("A story with no title is shown as a note, without a cover. Give it a title to show this.")
					coverId == null -> Muted("Or tap a photo in the story below and choose “Use as cover photo”.")
				}
				coverError?.let { ErrorText(it) }
			}

			val blocks = doc.blocks
			if (blocks.isNotEmpty()) {
				HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
				Eyebrow("Blocks (${blocks.size})")
				blocks.forEach { b ->
					val id = b.str("id") ?: return@forEach
					BlockCard(
						story = s, block = b, imgBase = settings.base,
						onEdit = ::edit,
						onPending = ::addPending,
						onSetCover = { ref -> setCover(ref) },
						onRemove = { pendingRemove = id },
						onMove = { delta -> edit { it.moveBlock(id, delta) } }
					)
				}
			}

			// Words between the pictures: the same kinds of block the web editor
			// offers. Each is added at the end and moved with its arrows.
			Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Eyebrow("Add")
				Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					OutlinedButton(onClick = { edit { it.appendBlock(StoryDoc.textBlock("")) } }) { Text("Text") }
					OutlinedButton(onClick = { edit { it.appendBlock(StoryDoc.headingBlock()) } }) { Text("Heading") }
					OutlinedButton(onClick = { edit { it.appendBlock(StoryDoc.codeBlock()) } }) { Text("Code") }
					OutlinedButton(onClick = { edit { it.appendBlock(StoryDoc.asideBlock()) } }) { Text("Aside") }
					OutlinedButton(onClick = { edit { it.appendBlock(StoryDoc.pullBlock("")) } }) { Text("Quote") }
				}
			}

			HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
			// One control, as in the web editor. Draft leaves the story's public/private
			// setting alone; Private and Public both publish on the next sync.
			Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Eyebrow("Who can see it")
				val status = if (s.isDraft) "draft" else if (s.isPrivate) "private" else "public"
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					FilterChip(selected = status == "draft", onClick = { edit { it.with("draft", true) } }, label = { Text("Draft") })
					FilterChip(selected = status == "private", onClick = { edit { it.with("draft", false).with("visibility", "private") } }, label = { Text("Private") })
					// Going public asks first, because it can't be fully undone.
					FilterChip(selected = status == "public", onClick = { if (status != "public") confirmPublic = true }, label = { Text("Public") })
				}
				Muted(
					when (status) {
						"draft" -> "Only you. Choose Private or Public to publish it on the next sync."
						"private" -> "Published to the members you've invited."
						else -> "Anyone can read it" + (s.slug?.let { " at /stories/$it" } ?: "") + "."
					}
				)
			}
		}
		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
		Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 4.dp)) {
			CaptureBar(onCapture = ::addCapture)
		}
	}

	if (confirmPublic) {
		AlertDialog(
			onDismissRequest = { confirmPublic = false },
			title = { Text("Publish this story publicly?") },
			text = { Text("Anyone will be able to read it and see its photos. If you make it private again later, copies already fetched or cached can stay reachable for a while.") },
			confirmButton = {
				TextButton(onClick = {
					confirmPublic = false
					edit { it.with("draft", false).with("visibility", "public") }
				}) { Text("Publish publicly") }
			},
			dismissButton = { TextButton(onClick = { confirmPublic = false }) { Text("Not yet") } }
		)
	}

	if (confirmDelete) {
		AlertDialog(
			onDismissRequest = { confirmDelete = false },
			title = { Text("Remove from this phone?") },
			text = { Text(if (s.slug != null) "The copy on the site stays; delete it there if you mean it." else "This story hasn't been synced — it will be gone.") },
			confirmButton = {
				TextButton(onClick = {
					confirmDelete = false
					scope.launch { app.storyStore.delete(localId); onClose() }
				}) { Text("Remove", color = c.wax) }
			},
			dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
		)
	}

	pendingRemove?.let { id ->
		AlertDialog(
			onDismissRequest = { pendingRemove = null },
			title = { Text("Remove this block?") },
			text = { Text("Media not yet uploaded is deleted from the phone; files already on the site stay there.") },
			confirmButton = {
				TextButton(onClick = {
					pendingRemove = null
					scope.launch {
						val cur = app.storyStore.get(localId) ?: return@launch
						val refs = cur.doc.blocks.firstOrNull { it.str("id") == id }?.placeholders() ?: emptySet()
						// A photo that is also the cover keeps its bytes: the cover still needs them.
						val dropped = cur.pending.filter { it.placeholder in refs && it.placeholder != cur.doc.str("coverImageId") }
						app.storyStore.update(localId) { c2 ->
							c2.copy(doc = c2.doc.removeBlock(id), pending = c2.pending.filterNot { p -> dropped.any { it.id == p.id } }, docSync = SyncState.LOCAL)
						}
						dropped.forEach { app.blobStore.delete(it.blobId) }
					}
				}) { Text("Remove", color = c.wax) }
			},
			dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("Cancel") } }
		)
	}
}

/** Story `date` (yyyy-mm-dd) as an instant at local noon, for the picker and for astronomy. */
private fun dateInstant(date: String?): Instant {
	val d = runCatching { LocalDate.parse(date ?: "") }.getOrNull() ?: LocalDate.now(ZoneId.systemDefault())
	return if (d == LocalDate.now(ZoneId.systemDefault())) Instant.now() else d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
}

private fun isoToDate(iso: String): String =
	runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.getOrDefault(LocalDate.now().toString())
