package fartingaround.capture.capture

import fartingaround.capture.model.Coords
import fartingaround.capture.model.WeatherSnapshot
import fartingaround.capture.model.nowIso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.roundToInt

/** Current conditions from Open-Meteo (free, no key). Null on any failure. */
object Weather {
	private val WMO = mapOf(
		0 to "Clear", 1 to "Mainly clear", 2 to "Partly cloudy", 3 to "Overcast",
		45 to "Fog", 48 to "Depositing rime fog",
		51 to "Light drizzle", 53 to "Drizzle", 55 to "Heavy drizzle",
		56 to "Freezing drizzle", 57 to "Heavy freezing drizzle",
		61 to "Light rain", 63 to "Rain", 65 to "Heavy rain",
		66 to "Freezing rain", 67 to "Heavy freezing rain",
		71 to "Light snow", 73 to "Snow", 75 to "Heavy snow", 77 to "Snow grains",
		80 to "Rain showers", 81 to "Heavy rain showers", 82 to "Violent rain showers",
		85 to "Snow showers", 86 to "Heavy snow showers",
		95 to "Thunderstorm", 96 to "Thunderstorm with hail", 99 to "Severe thunderstorm with hail"
	)

	suspend fun fetch(client: OkHttpClient, c: Coords): WeatherSnapshot? = withContext(Dispatchers.IO) {
		runCatching {
			val url = "https://api.open-meteo.com/v1/forecast?latitude=${c.lat}&longitude=${c.lon}" +
				"&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
				"&temperature_unit=celsius&wind_speed_unit=kmh"
			client.newCall(Request.Builder().url(url).build()).execute().use { res ->
				if (!res.isSuccessful) return@runCatching null
				val cur = Json.parseToJsonElement(res.body!!.string()).jsonObject["current"]?.jsonObject
					?: return@runCatching null
				val tempC = cur["temperature_2m"]?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
				WeatherSnapshot(
					tempC = tempC,
					tempF = (tempC * 9 / 5 + 32).roundToInt(),
					conditions = WMO[cur["weather_code"]?.jsonPrimitive?.intOrNull] ?: "Unknown",
					windKph = cur["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull,
					humidity = cur["relative_humidity_2m"]?.jsonPrimitive?.doubleOrNull,
					fetchedAt = nowIso()
				)
			}
		}.getOrNull()
	}
}
