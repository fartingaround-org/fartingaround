package org.fartingaround.app.ui.consume

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.fartingaround.app.captureApp
import org.fartingaround.app.consume.AtlasPlate
import org.fartingaround.app.consume.AtlasRef
import org.fartingaround.app.consume.Story
import org.fartingaround.app.ui.components.Eyebrow
import org.fartingaround.app.ui.components.Muted
import org.fartingaround.app.ui.components.fmtDuration
import org.fartingaround.app.ui.editor.AudioPlayer
import org.fartingaround.app.ui.theme.LocalCaptureColors
import org.fartingaround.app.ui.theme.imageFilter
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How far the plate zooms: 1 = the whole frame fits the viewer. */
private const val MAX_ZOOM = 6f
/** Past this, the full-size render is fetched and "everything" labels appear. */
private const val ZOOMED = 1.6f

/** Label modes, as on the web viewer: off → the quiet set (tiers 0–1) → everything. */
private const val LABELS_OFF = "off"
private const val LABELS_SUBTLE = "subtle"
private const val LABELS_ALL = "everything"

private sealed interface PlateLoad {
	data object Loading : PlateLoad
	data class Ready(val plate: AtlasPlate) : PlateLoad
	data class Failed(val why: String) : PlateLoad
}

/**
 * An atlas plate, natively: the photograph with pinch-zoom, its objects
 * ringed and labelled (tap one for its card), a stars on/off switch where
 * the plate has a starless render, its alternate versions, and the spoken
 * note with its transcript.
 *
 * The plate document comes from the atlas's reader API ([AtlasRef.plateUrl]).
 * While it loads, and when it cannot be had (offline, a newer contract),
 * [fallback] — the item as a plain article — is what the reader sees.
 */
@Composable
fun PlateView(ref: AtlasRef, title: String, feedTitle: String, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
	val http = LocalContext.current.captureApp.api.http
	var attempt by remember(ref.plateUrl) { mutableIntStateOf(0) }
	val load by produceState<PlateLoad>(PlateLoad.Loading, ref.plateUrl, attempt) {
		value = PlateLoad.Loading
		value = try {
			PlateLoad.Ready(AtlasPlate.fetch(http, ref.plateUrl))
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			PlateLoad.Failed(e.message ?: "the atlas could not be reached")
		}
	}
	when (val s = load) {
		is PlateLoad.Ready -> PlateContent(s.plate, title, feedTitle, modifier)
		is PlateLoad.Loading -> Column(modifier) {
			Muted("Opening the plate…", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
			fallback()
		}
		is PlateLoad.Failed -> Column(modifier) {
			Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
				Muted("Showing the article: ${s.why}.", Modifier.weight(1f))
				TextButton(onClick = { attempt++ }) { Text("Try again") }
			}
			fallback()
		}
	}
}

@Composable
private fun PlateContent(plate: AtlasPlate, title: String, feedTitle: String, modifier: Modifier = Modifier) {
	var versionId by remember(plate.slug) { mutableStateOf(plate.live) }
	val version = plate.versions.firstOrNull { it.id == versionId } ?: plate.versions.first()
	val anns = version.annotations ?: plate.annotations
	var mode by remember(plate.slug) { mutableStateOf(LABELS_SUBTLE) }
	var starsOff by remember(plate.slug) { mutableStateOf(false) }
	// A version with its own solve may not carry the object whose card is open.
	var selected by remember(plate.slug, versionId) { mutableStateOf<AtlasPlate.Annotation?>(null) }
	var fullscreen by remember { mutableStateOf(false) }
	val hasEverything = anns.any { it.tier >= 2 }
	val uri = LocalUriHandler.current

	Column(modifier.verticalScroll(rememberScrollState())) {
		Column(Modifier.padding(horizontal = 16.dp)) {
			Eyebrow(feedTitle)
			Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 4.dp))
			plate.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
		}

		PlateCanvas(
			plate, version, mode, starsOff, selected, onPick = { selected = it },
			modifier = Modifier.padding(top = 12.dp).fillMaxWidth().aspectRatio(plate.aspect)
		)
		Muted("Pinch to zoom · tap an object · double-tap to zoom in", Modifier.padding(horizontal = 16.dp).padding(top = 6.dp))

		Row(
			Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp),
			horizontalArrangement = Arrangement.spacedBy(6.dp)
		) {
			if (anns.isNotEmpty()) {
				FilterChip(mode != LABELS_OFF, { mode = if (mode == LABELS_OFF) LABELS_SUBTLE else LABELS_OFF }, { Text("Labels") })
				if (hasEverything) FilterChip(mode == LABELS_ALL, { mode = if (mode == LABELS_ALL) LABELS_SUBTLE else LABELS_ALL }, { Text("Everything") })
			}
			if (version.image.starless != null) FilterChip(starsOff, { starsOff = !starsOff }, { Text("Starless") })
			FilterChip(false, { fullscreen = true }, { Text("Full screen") })
		}
		if (plate.versions.size > 1) {
			Row(
				Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
				horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
			) {
				Muted("Version")
				plate.versions.forEach { v -> FilterChip(v.id == version.id, { versionId = v.id; starsOff = false }, { Text(v.label) }) }
			}
		}

		selected?.let { ObjectCard(it.card, plate.glossary, onClose = { selected = null }, modifier = Modifier.padding(horizontal = 16.dp).padding(top = 10.dp)) }

		plate.audio?.let { SpokenNote(it, Modifier.padding(horizontal = 16.dp).padding(top = 14.dp)) }

		plate.notes?.let { Prose(it, Modifier.padding(horizontal = 16.dp).padding(top = 14.dp)) }

		// the rows follow the version on screen: each is its own stack, with its own subs and nights
		val facts = version.facts ?: plate.facts
		if (facts.isNotEmpty()) {
			HorizontalDivider(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp))
			Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) { facts.forEach { FactRow(it) } }
		}
		plate.url?.let { link ->
			TextButton(onClick = { uri.openUri(link) }, modifier = Modifier.padding(horizontal = 8.dp).padding(top = 4.dp, bottom = 24.dp)) { Text("Open on the atlas") }
		}
	}

	if (fullscreen) {
		Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
			BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
				// The viewer is the whole screen: the plate starts fitted inside it and grows to fill it as you zoom.
				PlateCanvas(plate, version, mode, starsOff, selected, onPick = { selected = it }, modifier = Modifier.fillMaxSize())
				IconButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopEnd)) {
					Icon(Icons.Filled.Close, contentDescription = "Leave full screen", tint = Color.White)
				}
				selected?.let {
					Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * 0.5f).verticalScroll(rememberScrollState()).padding(12.dp)) {
						ObjectCard(it.card, plate.glossary, onClose = { selected = null })
					}
				}
			}
		}
	}
}

/** Where the plate sits in the viewer at rest: fitted whole and centred, with bars beside or above it when the viewer is another shape. */
private fun plateRect(box: IntSize, aspect: Float): Rect {
	val bw = box.width.toFloat()
	val bh = box.height.toFloat()
	if (bw <= 0f || bh <= 0f) return Rect(0f, 0f, bw, bh)
	val w = if (bw / bh > aspect) bh * aspect else bw
	val h = w / aspect
	val left = (bw - w) / 2f
	val top = (bh - h) / 2f
	return Rect(left, top, left + w, top + h)
}

/** Where a frame sits inside the plate's rectangle: all of it, or — for a version with its own crop — fitted and centred in it. */
private fun frameRect(within: Rect, plate: AtlasPlate, frame: AtlasPlate.Frame?): Rect {
	if (frame == null || plate.width <= 0 || plate.height <= 0) return within
	val k = min(plate.width.toFloat() / frame.width, plate.height.toFloat() / frame.height)
	val w = frame.width * k / plate.width * within.width
	val h = frame.height * k / plate.height * within.height
	val left = within.left + (within.width - w) / 2f
	val top = within.top + (within.height - h) / 2f
	return Rect(left, top, left + w, top + h)
}

/**
 * The photograph and its overlay. The image layers are scaled and moved as
 * one; the rings and labels are drawn over them at screen size from the same
 * numbers, so they stay crisp and a label never grows with the zoom.
 */
@Composable
private fun PlateCanvas(
	plate: AtlasPlate,
	version: AtlasPlate.Version,
	mode: String,
	starsOff: Boolean,
	selected: AtlasPlate.Annotation?,
	onPick: (AtlasPlate.Annotation?) -> Unit,
	modifier: Modifier = Modifier
) {
	val context = LocalContext.current
	val density = LocalDensity.current
	val pick by rememberUpdatedState(onPick)
	val ink = if (LocalCaptureColors.current.isAstro) Color(0xFFD04A3A) else Color(0xFFECE8F4)

	var scale by remember { mutableFloatStateOf(1f) }
	var offset by remember { mutableStateOf(Offset.Zero) }
	var box by remember { mutableStateOf(IntSize.Zero) }
	// Once asked for, the full-size layer stays: taking it away again would only make the picture flicker.
	var wantFull by remember(version.id) { mutableStateOf(false) }
	val starAlpha by animateFloatAsState(if (starsOff) 1f else 0f, label = "starless")

	val anns = version.annotations ?: plate.annotations
	val visible = remember(anns, mode) {
		when (mode) {
			LABELS_OFF -> emptyList()
			LABELS_SUBTLE -> anns.filter { it.tier <= 1 }
			else -> anns
		}
	}
	val labelStyle = MaterialTheme.typography.labelSmall
	val measurer = rememberTextMeasurer()
	val labels = remember(visible, labelStyle) { visible.map { measurer.measure(it.name, labelStyle) } }
	val area = remember(box, plate.aspect) { plateRect(box, plate.aspect) }
	val frame = remember(area, version.frame, plate.width, plate.height) { frameRect(area, plate, version.frame) }

	// fraction of the frame → screen px, through the current zoom and pan
	fun sx(fx: Double): Float = ((frame.left + fx * frame.width) * scale + offset.x).toFloat()
	fun sy(fy: Double): Float = ((frame.top + fy * frame.height) * scale + offset.y).toFloat()
	fun lengthPx(fractionOfWidth: Double): Float = (fractionOfWidth * frame.width * scale).toFloat()
	fun markRadius(a: AtlasPlate.Annotation): Float {
		val e = a.ellipse
		return if (e != null) lengthPx(max(e.rx, e.ry)) else if (a.r != null) lengthPx(a.r) else 0f
	}
	fun labelShown(a: AtlasPlate.Annotation) = a.tier < 2 || scale > ZOOMED
	fun labelTopLeft(a: AtlasPlate.Annotation, height: Int) = Offset(sx(a.lx), sy(a.ly) - height)
	// Along each axis: while the zoomed plate is smaller than the viewer it sits centred; once it is
	// bigger it must keep covering the viewer, so it cannot be dragged off its own edge.
	fun clampAxis(o: Float, s: Float, start: Float, end: Float, viewer: Float): Float {
		val size = (end - start) * s
		return if (size <= viewer) (viewer - size) / 2f - start * s else o.coerceIn(viewer - end * s, -start * s)
	}
	fun clamp(o: Offset, s: Float) = Offset(clampAxis(o.x, s, area.left, area.right, box.width.toFloat()), clampAxis(o.y, s, area.top, area.bottom, box.height.toFloat()))
	fun zoomTo(next: Float, anchor: Offset, pan: Offset = Offset.Zero) {
		offset = clamp(anchor - (anchor - offset) * (next / scale) + pan, next)
		scale = next
		if (next > ZOOMED) wantFull = true
	}

	/**
	 * The object under a tap. A small mark is a disc with some slop; a big
	 * outline (a nebula around a cluster) only answers on its ring, so it
	 * cannot swallow taps meant for what sits inside it. Of several, the
	 * smallest wins.
	 */
	fun hit(p: Offset): AtlasPlate.Annotation? {
		val slop = with(density) { 22.dp.toPx() }
		val big = with(density) { 48.dp.toPx() }
		val ring = with(density) { 14.dp.toPx() }
		val pad = with(density) { 6.dp.toPx() }
		var best: AtlasPlate.Annotation? = null
		var bestR = Float.MAX_VALUE
		visible.forEachIndexed { i, a ->
			val geom = markRadius(a)
			val d = (p - Offset(sx(a.x), sy(a.y))).getDistance()
			val onMark = if (geom > big) abs(d - geom) <= ring else d <= geom + slop
			val size = labels[i].size
			val tl = labelTopLeft(a, size.height)
			val onLabel = labelShown(a) && p.x >= tl.x - pad && p.x <= tl.x + size.width + pad && p.y >= tl.y - pad && p.y <= tl.y + size.height + pad
			if ((onMark || onLabel) && geom < bestR) { best = a; bestR = geom }
		}
		return best
	}

	Box(
		modifier
			.clipToBounds()
			.background(Color.Black)
			.onSizeChanged { box = it }
			.pointerInput(version.id, area) {
				awaitEachGesture {
					awaitFirstDown(requireUnconsumed = false)
					do {
						val event = awaitPointerEvent()
						val fingers = event.changes.count { it.pressed }
						// One finger at rest scale belongs to the page's own scrolling; two fingers, or any drag once zoomed, are ours.
						if (fingers > 1 || scale > 1.01f) {
							val centroid = event.calculateCentroid(useCurrent = false)
							val anchor = if (centroid == Offset.Unspecified) Offset(box.width / 2f, box.height / 2f) else centroid
							zoomTo((scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM), anchor, event.calculatePan())
							event.changes.forEach { if (it.positionChanged()) it.consume() }
						}
					} while (event.changes.any { it.pressed })
				}
			}
			.pointerInput(visible, labels, frame) {
				detectTapGestures(
					onDoubleTap = { p -> zoomTo(if (scale > ZOOMED) 1f else 3f, p) },
					onTap = { p -> pick(hit(p)) }
				)
			}
	) {
		Box(
			Modifier.fillMaxSize().graphicsLayer {
				scaleX = scale; scaleY = scale
				translationX = offset.x; translationY = offset.y
				transformOrigin = TransformOrigin(0f, 0f)
			}
		) {
			// Every layer is fitted to the plate's own rectangle, so a version with a different crop lands where its overlay does.
			val layer = Modifier.align(Alignment.Center).aspectRatio(plate.aspect, matchHeightConstraintsFirst = box.height > 0 && box.width.toFloat() / box.height > plate.aspect)
			val src = version.image.src
			AsyncImage(model = src, contentDescription = "${plate.title}, astrophotograph", contentScale = ContentScale.Fit, colorFilter = imageFilter(), modifier = layer)
			if (wantFull) {
				// The display render at its own size (up to 4096 px), laid over the screen-sized decode once the
				// reader zooms in. Not `image.full`: a 6000 px master is a bitmap too big for Android to draw in
				// one piece — going deeper than this wants the atlas's tile pyramid.
				val request = remember(src) { ImageRequest.Builder(context).data(src).size(coil3.size.Size.ORIGINAL).build() }
				AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Fit, colorFilter = imageFilter(), modifier = layer)
			}
			val starless = version.image.starless
			// mounted while it is on or still fading out, so a plate nobody switches never downloads it
			if (starless != null && (starsOff || starAlpha > 0f)) {
				AsyncImage(model = starless, contentDescription = null, contentScale = ContentScale.Fit, colorFilter = imageFilter(), alpha = starAlpha, modifier = layer)
			}
		}
		Canvas(Modifier.fillMaxSize()) {
			val thin = Stroke(width = 1.dp.toPx())
			val bold = Stroke(width = 2.dp.toPx())
			visible.forEachIndexed { i, a ->
				val chosen = a === selected
				val color = ink.copy(alpha = if (chosen) 1f else when (a.tier) { 0 -> 0.85f; 1 -> 0.6f; else -> 0.4f })
				val stroke = if (chosen) bold else thin
				val c = Offset(sx(a.x), sy(a.y))
				val e = a.ellipse
				if (e != null) {
					val rx = lengthPx(e.rx)
					val ry = lengthPx(e.ry)
					rotate(e.angle.toFloat(), c) { drawOval(color, topLeft = Offset(c.x - rx, c.y - ry), size = Size(rx * 2f, ry * 2f), style = stroke) }
				} else if (a.r != null) {
					drawCircle(color, radius = lengthPx(a.r), center = c, style = stroke)
				}
				if (labelShown(a)) drawText(labels[i], color = color, topLeft = labelTopLeft(a, labels[i].size.height))
			}
		}
	}
}

/** An explanation that is open on a card: the entry, and the sentence written for this object's own value. */
private data class OpenGloss(val gloss: AtlasPlate.Gloss, val forThis: String?)

/**
 * What the atlas says about one object: its own words first, Wikipedia's —
 * credited — in a box of their own. The words a reader may not know (a fact's
 * label, the object's type, the catalogue in the eyebrow) are tappable and
 * turn the card over to their explanation, as on the site.
 */
@Composable
private fun ObjectCard(card: AtlasPlate.Card, glossary: Map<String, AtlasPlate.Gloss>, onClose: () -> Unit, modifier: Modifier = Modifier) {
	val uri = LocalUriHandler.current
	val scheme = MaterialTheme.colorScheme
	val wax = LocalCaptureColors.current.wax
	var open by remember(card) { mutableStateOf<OpenGloss?>(null) }
	// A pointer whose entry the plate did not send is simply not tappable.
	fun explain(ref: AtlasPlate.GlossRef?): (() -> Unit)? = ref?.let { r -> glossary[r.key]?.let { g -> { open = OpenGloss(g, r.forThis) } } }

	Column(modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(scheme.surfaceVariant).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 14.dp)) {
		val o = open
		if (o != null) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text("← back to ${card.name}", color = wax, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).clickable { open = null }.padding(vertical = 12.dp))
				IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
			}
			Column(Modifier.padding(end = 10.dp)) {
				Text(o.gloss.title, style = MaterialTheme.typography.titleLarge)
				o.forThis?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
				o.gloss.body.forEach { para -> Text(para, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
				val more = o.gloss.moreUrl
				if (more != null) TextButton(onClick = { uri.openUri(more) }) { Text("${o.gloss.moreLabel ?: "Read more"} →") }
			}
			return@Column
		}
		Row(verticalAlignment = Alignment.Top) {
			Column(Modifier.weight(1f).padding(top = 8.dp)) {
				if (card.eyebrowParts.any { explain(it.gloss) != null }) {
					// the eyebrow, with the segments that have an explanation tappable
					Row(Modifier.horizontalScroll(rememberScrollState())) {
						card.eyebrowParts.forEachIndexed { i, part ->
							if (i > 0) Eyebrow(" · ")
							val tap = explain(part.gloss)
							if (tap != null) Text(part.text.uppercase(), style = MaterialTheme.typography.labelSmall, color = wax, modifier = Modifier.clickable(onClick = tap))
							else Eyebrow(part.text)
						}
					}
				} else card.eyebrow?.let { Eyebrow(it) }
				Text(card.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 2.dp))
			}
			IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
		}
		Column(Modifier.padding(end = 10.dp)) {
			card.blurb?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
			if (card.facts.isNotEmpty()) Column(Modifier.padding(top = 8.dp)) { card.facts.forEach { FactRow(it, onLabel = explain(it.gloss), onValue = explain(it.valueGloss)) } }
			card.wiki?.let { w ->
				Column(Modifier.padding(top = 10.dp).fillMaxWidth().border(1.dp, scheme.outlineVariant, RoundedCornerShape(4.dp)).padding(10.dp)) {
					Eyebrow("From Wikipedia")
					w.extract?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
					w.url?.let { link -> TextButton(onClick = { uri.openUri(link) }) { Text("Read the article") } }
					Muted("Wikipedia text, CC BY-SA")
				}
			}
		}
	}
}

/** A label and its value. With [onLabel] or [onValue] that word is tappable — in the wax colour, the label with a small info mark — and opens its explanation. */
@Composable
private fun FactRow(f: AtlasPlate.Fact, onLabel: (() -> Unit)? = null, onValue: (() -> Unit)? = null) {
	val wax = LocalCaptureColors.current.wax
	Row(Modifier.padding(vertical = 3.dp)) {
		if (onLabel != null) {
			Row(Modifier.width(128.dp).clickable(onClickLabel = "What ${f.label} means", onClick = onLabel), verticalAlignment = Alignment.CenterVertically) {
				Text(f.label, style = MaterialTheme.typography.bodySmall, color = wax, modifier = Modifier.weight(1f, fill = false))
				Icon(Icons.Outlined.Info, contentDescription = null, tint = wax, modifier = Modifier.padding(start = 4.dp).size(13.dp))
			}
		} else Muted(f.label, Modifier.width(128.dp))
		if (onValue != null) Text(f.value, style = MaterialTheme.typography.bodyMedium, color = wax, modifier = Modifier.weight(1f).clickable(onClick = onValue))
		else Text(f.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
	}
}

/** The plate's spoken note; with word timings, the word being said is bold. */
@Composable
private fun SpokenNote(audio: AtlasPlate.Audio, modifier: Modifier = Modifier) {
	var positionMs by remember(audio.src) { mutableLongStateOf(0L) }
	var playing by remember(audio.src) { mutableStateOf(false) }
	val scheme = MaterialTheme.colorScheme
	Column(modifier) {
		AudioPlayer(Uri.parse(audio.src)) { pos, isPlaying -> positionMs = pos; playing = isPlaying }
		val length = fmtDuration(audio.durationMs)
		Muted(if (length.isNotBlank()) "Spoken note · $length" else "Spoken note", Modifier.padding(top = 4.dp))
		if (audio.words.isNotEmpty()) {
			Text(transcript(audio.words, positionMs, playing, scheme.onBackground, scheme.onSurfaceVariant, LocalCaptureColors.current.pencil), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
		} else {
			audio.transcript?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
		}
	}
}

/** The words, coloured by where the clip is: said, being said, still to come. Plain until it is played. */
private fun transcript(words: List<Story.Word>, positionMs: Long, playing: Boolean, now: Color, said: Color, ahead: Color) = buildAnnotatedString {
	val following = playing || positionMs > 0
	val t = positionMs / 1000.0
	for (w in words) {
		val style = when {
			!following -> SpanStyle(color = said)
			t >= w.s && t < w.e -> SpanStyle(color = now, fontWeight = FontWeight.Bold)
			t >= w.e -> SpanStyle(color = said)
			else -> SpanStyle(color = ahead)
		}
		withStyle(style) { append(w.t) }
		append(" ")
	}
}
