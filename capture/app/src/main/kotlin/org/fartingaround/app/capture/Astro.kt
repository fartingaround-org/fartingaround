package org.fartingaround.app.capture

import org.fartingaround.app.model.AstroSnapshot
import org.fartingaround.app.model.Coords
import java.time.Instant
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan

/**
 * Moon phase and sun events for a moment + place. A direct port of the
 * relevant pieces of suncalc (mourner/suncalc, BSD-2) — the same subset the
 * TS app carried in context.ts — so results match what's already in KV.
 * Pure math, works offline.
 */
object Astro {
	fun compute(coords: Coords, at: Instant = Instant.now()): AstroSnapshot {
		val ms = at.toEpochMilli()
		val (phase, illumination) = moonPhaseAt(ms)
		val sun = sunTimesAt(ms, coords.lat, coords.lon)
		return AstroSnapshot(
			moonPhase = phase,
			moonPhaseLabel = phaseLabel(phase),
			moonIllumination = illumination,
			sunrise = sun.sunrise?.toString() ?: "",
			sunset = sun.sunset?.toString() ?: "",
			astronomicalDawn = sun.astroDawn?.toString(),
			astronomicalDusk = sun.astroDusk?.toString()
		)
	}

	private const val RAD = PI / 180
	private const val DAY_MS = 86_400_000.0
	private const val J1970 = 2440588.0
	private const val J2000 = 2451545.0
	private val OBLIQUITY = RAD * 23.4397

	private fun toJulian(ms: Long) = ms / DAY_MS - 0.5 + J1970
	private fun toDays(ms: Long) = toJulian(ms) - J2000
	private fun fromJulian(j: Double): Instant = Instant.ofEpochMilli(((j + 0.5 - J1970) * DAY_MS).toLong())

	private fun rightAscension(l: Double, b: Double) =
		atan2(sin(l) * cos(OBLIQUITY) - tan(b) * sin(OBLIQUITY), cos(l))
	private fun declination(l: Double, b: Double) =
		asin(sin(b) * cos(OBLIQUITY) + cos(b) * sin(OBLIQUITY) * sin(l))

	private fun solarMeanAnomaly(d: Double) = RAD * (357.5291 + 0.98560028 * d)
	private fun eclipticLongitude(m: Double): Double {
		val c = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
		val p = RAD * 102.9372
		return m + c + p + PI
	}

	private data class Eq(val ra: Double, val dec: Double, val dist: Double = 0.0)

	private fun sunCoords(d: Double): Eq {
		val l = eclipticLongitude(solarMeanAnomaly(d))
		return Eq(ra = rightAscension(l, 0.0), dec = declination(l, 0.0))
	}

	private fun moonCoords(d: Double): Eq {
		val bigL = RAD * (218.316 + 13.176396 * d)
		val bigM = RAD * (134.963 + 13.064993 * d)
		val bigF = RAD * (93.272 + 13.22935 * d)
		val l = bigL + RAD * 6.289 * sin(bigM)
		val b = RAD * 5.128 * sin(bigF)
		val dt = 385001 - 20905 * cos(bigM)
		return Eq(ra = rightAscension(l, b), dec = declination(l, b), dist = dt)
	}

	/** Returns (phase 0..1, illumination 0..1). */
	private fun moonPhaseAt(ms: Long): Pair<Double, Double> {
		val d = toDays(ms)
		val s = sunCoords(d)
		val m = moonCoords(d)
		val sdist = 149_598_000.0
		val phi = acos(sin(s.dec) * sin(m.dec) + cos(s.dec) * cos(m.dec) * cos(s.ra - m.ra))
		val inc = atan2(sdist * sin(phi), m.dist - sdist * cos(phi))
		val angle = atan2(
			cos(s.dec) * sin(s.ra - m.ra),
			sin(s.dec) * cos(m.dec) - cos(s.dec) * sin(m.dec) * cos(s.ra - m.ra)
		)
		val phase = 0.5 + 0.5 * inc * (if (angle < 0) -1 else 1) / PI
		val illumination = (1 + cos(inc)) / 2
		return phase to illumination
	}

	fun phaseLabel(phase: Double): String = when {
		phase < 0.03 || phase > 0.97 -> "New moon"
		phase < 0.22 -> "Waxing crescent"
		phase < 0.28 -> "First quarter"
		phase < 0.47 -> "Waxing gibbous"
		phase < 0.53 -> "Full moon"
		phase < 0.72 -> "Waning gibbous"
		phase < 0.78 -> "Last quarter"
		else -> "Waning crescent"
	}

	// ── Sun rise/set ──────────────────────────────────────────────────────────
	private const val J0 = 0.0009

	private fun julianCycle(d: Double, lw: Double) = round(d - J0 - lw / (2 * PI))
	private fun approxTransit(ht: Double, lw: Double, n: Double) = J0 + (ht + lw) / (2 * PI) + n
	private fun solarTransitJ(ds: Double, m: Double, l: Double) = J2000 + ds + 0.0053 * sin(m) - 0.0069 * sin(2 * l)
	private fun hourAngle(h: Double, phi: Double, d: Double) =
		acos((sin(h) - sin(phi) * sin(d)) / (cos(phi) * cos(d)))
	private fun getSetJ(h: Double, lw: Double, phi: Double, dec: Double, n: Double, m: Double, l: Double): Double {
		val w = hourAngle(h, phi, dec)
		val a = approxTransit(w, lw, n)
		return solarTransitJ(a, m, l)
	}

	private data class SunTimes(val sunrise: Instant?, val sunset: Instant?, val astroDawn: Instant?, val astroDusk: Instant?)

	private fun sunTimesAt(ms: Long, lat: Double, lon: Double): SunTimes {
		val lw = RAD * -lon
		val phi = RAD * lat
		val d = toDays(ms)
		val n = julianCycle(d, lw)
		val ds = approxTransit(0.0, lw, n)
		val m = solarMeanAnomaly(ds)
		val l = eclipticLongitude(m)
		val dec = declination(l, 0.0)
		val jnoon = solarTransitJ(ds, m, l)

		val sunset = getSetJ(RAD * -0.833, lw, phi, dec, n, m, l)
		val sunrise = jnoon - (sunset - jnoon)
		val astroDusk = getSetJ(RAD * -18, lw, phi, dec, n, m, l)
		val astroDawn = jnoon - (astroDusk - jnoon)

		// hourAngle returns NaN above the polar circles (no rise/set that day).
		fun inst(j: Double) = if (j.isFinite()) fromJulian(j) else null
		return SunTimes(inst(sunrise), inst(sunset), inst(astroDawn), inst(astroDusk))
	}
}
