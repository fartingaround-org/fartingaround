package fartingaround.capture.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/*
 * Shared value types. The wire format is the Story JSON documented in
 * src/lib/types.js on the web side (and docs/api-v1.md); these are the few
 * pieces we type on the phone because the capture code produces them.
 *
 * Conventions:
 *  - Optional fields are nullable with a null default. `AppJson` is configured
 *    with explicitNulls = false, so a null is simply omitted when encoding and
 *    a missing key decodes to null — same as `undefined` in JS.
 */

/** JSON codec shared by storage and the API client. */
val AppJson: Json = Json {
	ignoreUnknownKeys = true   // the server may add fields we don't model yet
	explicitNulls = false      // omit nulls on encode, tolerate missing on decode
	encodeDefaults = true
}

@Serializable
data class Coords(
	val lat: Double,
	val lon: Double,
	val accuracy: Double? = null
)

/** Weather at capture time, from Open-Meteo. */
@Serializable
data class WeatherSnapshot(
	val tempC: Double,
	val tempF: Int,
	val conditions: String,
	val windKph: Double? = null,
	val humidity: Double? = null,
	val source: String = "open-meteo",
	val fetchedAt: String
)

/** Moon + sun state at capture time and place; computed locally. */
@Serializable
data class AstroSnapshot(
	val moonPhase: Double,
	val moonPhaseLabel: String,
	val moonIllumination: Double,
	val sunrise: String,
	val sunset: String,
	val astronomicalDawn: String? = null,
	val astronomicalDusk: String? = null
)

@Serializable
enum class SyncState {
	@SerialName("local") LOCAL,
	@SerialName("syncing") SYNCING,
	@SerialName("synced") SYNCED,
	@SerialName("error") ERROR
}

fun nowIso(): String = Instant.now().toString()

fun newId(): String = UUID.randomUUID().toString()

/** Short url-safe id for blocks and media, like the server mints: `au-k3Jd8Qw1`. */
fun newBlockId(type: String): String {
	val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
	val r = java.security.SecureRandom()
	val tail = (1..9).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
	return "${type.take(2)}-$tail"
}
