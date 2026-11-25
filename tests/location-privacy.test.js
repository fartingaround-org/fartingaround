/** How precisely a story's location is published (src/lib/location-privacy.js). */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { DEFAULT_LOCATION_PRECISION, locationPrecisionOf, publicCoords, publicLocation } from '../src/lib/location-privacy.js';

const home = { lat: 44.977753, lon: -93.265011, accuracy: 4.2 };

test('the default is the approximate area', () => {
	assert.equal(DEFAULT_LOCATION_PRECISION, 'area');
	assert.deepEqual(publicCoords(home), { lat: 44.98, lon: -93.27, approximate: true });
	assert.deepEqual(publicCoords(home, 'area'), { lat: 44.98, lon: -93.27, approximate: true });
});

test('a missing or bad setting never publishes the exact point', () => {
	// 0 and 3 are what Array.prototype.map would pass as a second argument.
	for (const bad of [undefined, null, '', 'Exact', 'EXACT', 'precise', 0, 3, true, {}]) {
		assert.deepEqual(publicCoords(home, bad), { lat: 44.98, lon: -93.27, approximate: true }, String(bad));
	}
});

test('posts from one spot all land on the same point', () => {
	const seen = new Set();
	for (let i = 0; i < 200; i++) {
		// GPS jitter of a few metres around one address.
		const c = publicCoords({ lat: home.lat + (i % 7) * 0.00001, lon: home.lon - (i % 5) * 0.00001 }, 'area');
		seen.add(JSON.stringify(c));
	}
	assert.equal(seen.size, 1);
});

test('exact sends the point as stored, without the GPS accuracy', () => {
	assert.deepEqual(publicCoords(home, 'exact'), { lat: 44.977753, lon: -93.265011 });
});

test('hidden sends nothing', () => {
	assert.equal(publicCoords(home, 'hidden'), undefined);
});

test('no usable coordinates means none, whatever the setting', () => {
	for (const p of ['area', 'exact', 'hidden']) {
		assert.equal(publicCoords(undefined, p), undefined);
		assert.equal(publicCoords(null, p), undefined);
		assert.equal(publicCoords({ lat: 'x', lon: 1 }, p), undefined);
		assert.equal(publicCoords({ lat: 1 }, p), undefined);
	}
});

test('points near zero do not come out as -0', () => {
	const c = publicCoords({ lat: -0.001, lon: -0.004 }, 'area');
	assert.ok(c && Object.is(c.lat, 0) && Object.is(c.lon, 0));
});

test('locationPrecisionOf accepts only the three values', () => {
	assert.equal(locationPrecisionOf('area'), 'area');
	assert.equal(locationPrecisionOf('exact'), 'exact');
	assert.equal(locationPrecisionOf('hidden'), 'hidden');
	assert.equal(locationPrecisionOf('public'), undefined);
	assert.equal(locationPrecisionOf(undefined), undefined);
});

test('an ordinary place name goes out as written, at every setting', () => {
	for (const p of ['area', 'exact', 'hidden', undefined]) {
		assert.equal(publicLocation('North Loop, Minnesota, United States', p), 'North Loop, Minnesota, United States');
		assert.equal(publicLocation('Route 66, mile 12', p), 'Route 66, mile 12');
		assert.equal(publicLocation('', p), undefined);
		assert.equal(publicLocation(undefined, p), undefined);
	}
});

test('a place name that is really coordinates follows the setting', () => {
	const typed = '45.6052589, -94.4689599';
	assert.equal(publicLocation(typed), '≈ 45.61, -94.47');
	assert.equal(publicLocation(typed, 'area'), '≈ 45.61, -94.47');
	assert.equal(publicLocation('45.6052589 -94.4689599', 'area'), '≈ 45.61, -94.47');
	assert.equal(publicLocation(typed, 'bogus'), '≈ 45.61, -94.47');
	assert.equal(publicLocation(typed, 'hidden'), undefined);
	assert.equal(publicLocation(typed, 'exact'), typed);
	// Already no more precise than the area: nothing to hide.
	assert.equal(publicLocation('45.6, -94.47', 'area'), '45.6, -94.47');
});

test('a Plus Code as the place name is dropped unless locations are exact', () => {
	assert.equal(publicLocation('86P8XPHM+4X', 'area'), undefined);
	assert.equal(publicLocation('86p8xphm+4x Minneapolis', undefined), undefined);
	assert.equal(publicLocation('86P8XPHM+4X', 'hidden'), undefined);
	assert.equal(publicLocation('86P8XPHM+4X', 'exact'), '86P8XPHM+4X');
});
