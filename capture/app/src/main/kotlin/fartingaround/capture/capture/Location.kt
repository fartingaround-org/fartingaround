package fartingaround.capture.capture

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.CancellationSignal
import fartingaround.capture.model.Coords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import kotlin.coroutines.resume

/**
 * One-shot GPS fix via the framework LocationManager — no Play Services
 * dependency, works on de-Googled phones. ACCESS_FINE_LOCATION must already be
 * granted. Tries GPS first (accurate but slow to cold-start), falls back to the
 * fused/network provider if that times out.
 */
object Location {
	@SuppressLint("MissingPermission")
	suspend fun current(context: Context): Coords {
		val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
		val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
			.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
		if (providers.isEmpty()) throw IllegalStateException("Location is turned off on this device.")

		for (provider in providers) {
			val loc = withTimeoutOrNull(15_000) {
				suspendCancellableCoroutine<android.location.Location?> { cont ->
					val signal = CancellationSignal()
					cont.invokeOnCancellation { signal.cancel() }
					lm.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
				}
			}
			if (loc != null) {
				return Coords(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else null)
			}
		}
		throw IllegalStateException("Couldn't get a GPS fix. Try again outdoors.")
	}

	/**
	 * Reverse-geocode to a short "Neighbourhood, Region, Country" string via
	 * Nominatim (OpenStreetMap). Their usage policy wants a descriptive
	 * User-Agent — something the WebView couldn't set, but we can. Returns null
	 * on any failure so the caller leaves the Place field alone.
	 */
	suspend fun reverseGeocode(client: OkHttpClient, c: Coords): String? = withContext(Dispatchers.IO) {
		runCatching {
			val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2" +
				"&lat=${c.lat}&lon=${c.lon}&zoom=14&addressdetails=1"
			val req = Request.Builder().url(url)
				.header("User-Agent", "fartingaround-capture/2.0 (https://fartingaround.fun)")
				.header("Accept", "application/json")
				.header("Accept-Language", Locale.getDefault().toLanguageTag())
				.build()
			client.newCall(req).execute().use { res ->
				if (!res.isSuccessful) return@runCatching null
				val root = Json.parseToJsonElement(res.body!!.string()).jsonObject
				val addr = root["address"]?.jsonObject
				val get = { k: String -> addr?.get(k)?.jsonPrimitive?.content }
				val locality = listOf("neighbourhood", "suburb", "village", "town", "city", "hamlet", "county")
					.firstNotNullOfOrNull(get)
				val region = get("state") ?: get("region")
				val country = get("country")
				val parts = listOfNotNull(locality, region, country).distinct()
				parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
					?: root["display_name"]?.jsonPrimitive?.content
			}
		}.getOrNull()
	}
}
