package org.fartingaround.app.ui.consume

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.fartingaround.app.consume.Story
import org.fartingaround.app.ui.theme.LocalCaptureColors
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import android.os.Handler
import android.os.Looper

/**
 * Pins (and in 'path' mode a dashed line in time-taken order) under a photo
 * dump. Leaflet + OSM tiles in a WebView, the same approach as the website:
 * a native map SDK would be a lot of dependency for a handful of markers.
 * JavaScript is enabled only here, and the page is our own template.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun DumpMap(story: Story, dump: Story.Block.Dump, modifier: Modifier = Modifier, onOpen: (Int) -> Unit) {
	val c = LocalCaptureColors.current
	val stops = dump.photos.withIndex().filter { it.value.coords != null }
		.sortedWith(compareBy<IndexedValue<Story.DumpPhoto>> { it.value.takenAt == null }.thenBy { it.value.takenAt ?: "" }.thenBy { it.index })
	val data = buildJsonArray {
		for ((index, p) in stops) add(buildJsonObject {
			put("i", index); put("lat", p.coords!!.lat); put("lon", p.coords.lon)
			put("thumb", story.img(p.imageId, "w=96,h=96,fit=cover,q=70"))
		})
	}
	val line = "#%06X".format(0xFFFFFF and c.wax.toArgb())
	val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#m{margin:0;height:100%;background:#ddd}.pin{width:36px;height:36px;border-radius:50%;border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.4);background-size:cover;background-position:center}${if (c.isAstro) ".leaflet-tile{filter:grayscale(1) sepia(1) saturate(3) hue-rotate(-45deg) brightness(.35)}" else ""}</style>
</head><body><div id="m"></div><script>
const stops=$data, mode=${'"'}${dump.map}${'"'};
const map=L.map('m',{zoomControl:false,attributionControl:true});
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap'}).addTo(map);
const pts=stops.map(s=>[s.lat,s.lon]);
stops.forEach(s=>L.marker([s.lat,s.lon],{icon:L.divIcon({className:'',html:'<div class="pin" style="background-image:url('+s.thumb+')"></div>',iconSize:[36,36],iconAnchor:[18,18]})}).addTo(map).on('click',()=>Android.open(s.i)));
if(mode==='path'&&pts.length>1)L.polyline(pts,{color:'$line',weight:3,dashArray:'6 6',opacity:.9}).addTo(map);
map.fitBounds(L.latLngBounds(pts).pad(0.25),{maxZoom:15});
</script></body></html>"""
	AndroidView(
		modifier = modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
		factory = { ctx ->
			WebView(ctx).apply {
				settings.javaScriptEnabled = true
				settings.domStorageEnabled = false
				addJavascriptInterface(object {
					@JavascriptInterface fun open(i: Int) { Handler(Looper.getMainLooper()).post { onOpen(i) } }
				}, "Android")
			}
		},
		update = { web -> web.loadDataWithBaseURL("https://localhost/", html, "text/html", "utf-8", null) }
	)
}
