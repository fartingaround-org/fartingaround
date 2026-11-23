package org.fartingaround.app.ui.consume

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.fartingaround.app.captureApp
import org.fartingaround.app.consume.AtlasRef
import org.fartingaround.app.consume.Story
import org.fartingaround.app.ui.components.Eyebrow
import org.fartingaround.app.ui.components.Muted
import org.fartingaround.app.ui.components.fmtPublished
import org.fartingaround.app.ui.theme.LocalCaptureColors
import java.time.Instant

/**
 * Reads one item. Plain RSS mode: the feed's HTML in a WebView, restyled
 * to match whichever theme is on (paper or astro). Opening the item marks
 * it read. External links leave the app for the system browser.
 *
 * The enhanced `_fartingaround` renderer (dump grids, map path, audio)
 * will branch off here once `item.extensionJson != null`.
 */
@Composable
fun ItemScreen(itemId: String, onBack: () -> Unit) {
	val context = LocalContext.current
	val repo = context.captureApp.consume
	val item by remember(itemId) { repo.item(itemId) }.collectAsStateWithLifecycle(initialValue = null)
	val feeds by remember { repo.feeds }.collectAsStateWithLifecycle(initialValue = emptyList())

	LaunchedEffect(itemId) { repo.setRead(itemId, true) }

	val it = item ?: return
	val feed = feeds.firstOrNull { f -> f.url == it.feedUrl }
	val feedTitle = feed?.title ?: it.feedUrl
	val story = remember(it.id, it.extensionJson, feed?.enhancedBase) {
		val base = feed?.enhancedBase
		if (it.extensionJson != null && base != null) Story.parse(it.extensionJson, base, it.feedUrl) else null
	}

	// An atlas plate (an Outer Spiral Arm–style feed): opened natively, with the article underneath as the fallback.
	val plate = remember(it.id, it.extensionJson) { AtlasRef.parse(it.extensionJson, it.feedUrl) }

	Column(Modifier.fillMaxSize()) {
		ItemActions(it, onBack)
		if (plate != null) {
			PlateView(plate, title = it.title, feedTitle = feedTitle, modifier = Modifier.fillMaxSize()) {
				ArticleWebView(html = it.contentHtml ?: it.summary ?: "", baseUrl = it.url, modifier = Modifier.fillMaxSize())
			}
			return@Column
		}
		if (story != null) {
			// ✦ the enhanced view: blocks rendered natively
			StoryView(story, title = it.title, feedTitle = feedTitle, modifier = Modifier.fillMaxSize())
			return@Column
		}
		Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
			Eyebrow(feedTitle)
			if (!Story.isNoteTitle(it.title)) Text(it.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 4.dp))
			Muted(fmtPublished(it.publishedAt), Modifier.padding(top = 2.dp, bottom = 8.dp))
		}
		ArticleWebView(html = it.contentHtml ?: it.summary ?: "", baseUrl = it.url, modifier = Modifier.fillMaxSize())
	}
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArticleWebView(html: String, baseUrl: String?, modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val scheme = MaterialTheme.colorScheme
	val c = LocalCaptureColors.current
	fun hex(col: Color) = "#%06X".format(0xFFFFFF and col.toArgb())
	val css = """
		:root { color-scheme: ${if (c.isDark) "dark" else "light"}; }
		html { background: ${hex(scheme.background)}; }
		body { margin: 0 16px 48px; color: ${hex(scheme.onBackground)}; font: 17px/1.55 Georgia, 'Noto Serif', serif; word-wrap: break-word; }
		a { color: ${hex(c.wax)}; }
		img, video, iframe { max-width: 100%; height: auto; border-radius: 3px; ${if (c.isAstro) "filter: sepia(1) saturate(4) hue-rotate(-45deg) brightness(.55);" else ""} }
		blockquote { border-left: 2px solid ${hex(c.pencilSoft)}; margin: 1em 0; padding-left: 12px; color: ${hex(scheme.onSurfaceVariant)}; }
		pre, code { font-family: ui-monospace, monospace; font-size: 14px; background: ${hex(scheme.surfaceVariant)}; }
		pre { padding: 10px; overflow-x: auto; border-radius: 3px; }
		hr { border: 0; border-top: 1px solid ${hex(scheme.outlineVariant)}; }
		figure { margin: 1em 0; } figcaption { font-size: 14px; color: ${hex(c.pencil)}; }
		h1,h2,h3 { line-height: 1.2; } h1 { font-size: 1.5em; } h2 { font-size: 1.3em; } h3 { font-size: 1.1em; }
		table { border-collapse: collapse; max-width: 100%; } td, th { border: 1px solid ${hex(scheme.outlineVariant)}; padding: 4px 8px; }
	""".trimIndent()
	val doc = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>$css</style></head><body>$html</body></html>"

	AndroidView(
		modifier = modifier,
		factory = { ctx ->
			WebView(ctx).apply {
				settings.javaScriptEnabled = false
				settings.loadWithOverviewMode = true
				settings.useWideViewPort = true
				settings.builtInZoomControls = false
				setBackgroundColor(scheme.background.toArgb())
				webViewClient = object : WebViewClient() {
					override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
						// Taps on links go to the system browser; the WebView only ever shows our document.
						runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
						return true
					}
				}
			}
		},
		update = { web ->
			web.setBackgroundColor(scheme.background.toArgb())
			web.loadDataWithBaseURL(baseUrl?.let { Uri.parse(it).let { u -> "${u.scheme}://${u.host}/" } }, doc, "text/html", "utf-8", null)
		}
	)
}
