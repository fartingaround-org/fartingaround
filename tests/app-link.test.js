import test from 'node:test';
import assert from 'node:assert/strict';
import { appConnectLink } from '../src/lib/app-link.js';

test('the connect link carries the site and the token, safely encoded', () => {
	const link = appConnectLink('https://example.org/', 'abc123');
	assert.equal(link, 'fartingaround://connect?site=https%3A%2F%2Fexample.org&token=abc123');
	const u = new URL(appConnectLink('https://example.org:8443', 'a b&c=d'));
	assert.equal(u.protocol, 'fartingaround:');
	assert.equal(u.searchParams.get('site'), 'https://example.org:8443');
	assert.equal(u.searchParams.get('token'), 'a b&c=d');
});
