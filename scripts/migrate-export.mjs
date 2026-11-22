#!/usr/bin/env node
/**
 * Stage 1 of the move off Cloudflare: export everything into a folder.
 *
 *   node scripts/migrate-export.mjs ./migration-export
 *
 * Run it on your laptop (anywhere with your Cloudflare credentials). It needs:
 *   - wrangler logged in (`npx wrangler whoami`) — KV is read through it
 *   - in .dev.vars or the environment:
 *       CLOUDFLARE_ACCOUNT_ID, CLOUDFLARE_IMAGES_TOKEN     (Images API)
 *       R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY (R2 S3 API)
 *
 * Output layout (what scripts/migrate-import.mjs expects):
 *   kv/stories/<slug>.json        each story document
 *   kv/drafts/<id>.json           each draft document
 *   images/<id>/orig.<ext>        every Cloudflare Images original
 *   images/index.json             [{ id, ext, mime, filename, uploaded }]
 *   objects/<key>                 every object from both R2 buckets
 *   manifest.json                 counts + the key list, for the import to verify
 *
 * Re-runnable: anything already on disk is skipped, so an interrupted run
 * just picks up where it stopped. Nothing is deleted from Cloudflare.
 */

import { execFileSync } from 'node:child_process';
import { createHash, createHmac } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';

const KV_NAMESPACE_ID = '794196eaa7964062ba661d42d9ba29c6';
const BUCKETS = ['fartingaround-photos', 'fartingaround-audio-video'];
const CONCURRENCY = 4;

const out = path.resolve(process.argv[2] || './migration-export');
mkdirSync(out, { recursive: true });

// ── credentials ─────────────────────────────────────────────────────────────
const env = { ...readDotenv('.dev.vars'), ...process.env };
const need = (k) => {
	if (!env[k]) throw new Error(`${k} is not set (put it in .dev.vars or the environment)`);
	return env[k];
};
const accountId = need('CLOUDFLARE_ACCOUNT_ID');
const imagesToken = need('CLOUDFLARE_IMAGES_TOKEN');
const r2 = {
	accountId: env.R2_ACCOUNT_ID || accountId,
	accessKeyId: need('R2_ACCESS_KEY_ID'),
	secretAccessKey: need('R2_SECRET_ACCESS_KEY')
};

function readDotenv(file) {
	if (!existsSync(file)) return {};
	const o = {};
	for (const line of readFileSync(file, 'utf8').split('\n')) {
		const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
		if (m) o[m[1]] = m[2].replace(/^["']|["']$/g, '');
	}
	return o;
}

const manifest = { exportedAt: new Date().toISOString(), stories: [], drafts: [], images: 0, objects: [] };

// ── 1. KV via wrangler ──────────────────────────────────────────────────────
console.log('== 1/3 KV (stories + drafts) via wrangler');
const keys = JSON.parse(wrangler('kv', 'key', 'list', '--namespace-id', KV_NAMESPACE_ID, '--remote')).map((k) => k.name);
console.log(`   ${keys.length} keys`);
mkdirSync(path.join(out, 'kv/stories'), { recursive: true });
mkdirSync(path.join(out, 'kv/drafts'), { recursive: true });
for (const key of keys) {
	const m = key.match(/^(story|draft):(.+)$/);
	if (!m) continue; // index:stories / index:drafts are derivable
	const dest = path.join(out, 'kv', m[1] === 'story' ? 'stories' : 'drafts', `${safeName(m[2])}.json`);
	if (!existsSync(dest)) {
		const value = wrangler('kv', 'key', 'get', key, '--namespace-id', KV_NAMESPACE_ID, '--remote');
		JSON.parse(value); // make sure it's intact before we keep it
		writeFileSync(dest, value);
	}
	(m[1] === 'story' ? manifest.stories : manifest.drafts).push(m[2]);
	process.stdout.write(`   ${key}\n`);
}

function wrangler(...args) {
	return execFileSync('npx', ['--yes', 'wrangler@4', ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'], maxBuffer: 64 * 1024 * 1024 }).trim();
}

// ── 2. Cloudflare Images ────────────────────────────────────────────────────
console.log('== 2/3 Cloudflare Images originals');
const images = [];
for (let page = 1; ; page++) {
	const res = await cf(`/accounts/${accountId}/images/v1?page=${page}&per_page=100`);
	const list = res.result?.images || [];
	images.push(...list);
	if (list.length < 100) break;
}
console.log(`   ${images.length} images`);
const imageIndex = [];
await pool(images, async (img) => {
	const dir = path.join(out, 'images', img.id);
	mkdirSync(dir, { recursive: true });
	let entry = existsSync(path.join(dir, 'meta.json')) ? JSON.parse(readFileSync(path.join(dir, 'meta.json'), 'utf8')) : null;
	if (!entry || !existsSync(path.join(dir, `orig.${entry.ext}`))) {
		const r = await fetch(`https://api.cloudflare.com/client/v4/accounts/${accountId}/images/v1/${img.id}/blob`, {
			headers: { Authorization: `Bearer ${imagesToken}` }
		});
		if (!r.ok) throw new Error(`image ${img.id} blob: ${r.status} ${await r.text()}`);
		const mime = r.headers.get('content-type') || 'image/jpeg';
		const ext = extFromMime(mime, img.filename);
		await writeFile(path.join(dir, `orig.${ext}`), Buffer.from(await r.arrayBuffer()));
		entry = { id: img.id, ext, mime, filename: img.filename || null, uploaded: img.uploaded || null, meta: img.meta || null };
		writeFileSync(path.join(dir, 'meta.json'), JSON.stringify(entry));
	}
	imageIndex.push(entry);
	process.stdout.write(`   ${img.id} ${entry.filename || ''}\n`);
});
writeFileSync(path.join(out, 'images/index.json'), JSON.stringify(imageIndex, null, 1));
manifest.images = imageIndex.length;

async function cf(pathname) {
	const r = await fetch(`https://api.cloudflare.com/client/v4${pathname}`, { headers: { Authorization: `Bearer ${imagesToken}` } });
	const body = await r.json();
	if (!r.ok || body.success === false) throw new Error(`Cloudflare API ${pathname}: ${r.status} ${JSON.stringify(body.errors || body)}`);
	return body;
}

// ── 3. R2 objects via the S3 API ────────────────────────────────────────────
console.log('== 3/3 R2 objects');
for (const bucket of BUCKETS) {
	const keysInBucket = await listBucket(bucket);
	console.log(`   ${bucket}: ${keysInBucket.length} objects`);
	await pool(keysInBucket, async (key) => {
		const dest = path.join(out, 'objects', key);
		if (!existsSync(dest)) {
			mkdirSync(path.dirname(dest), { recursive: true });
			const r = await fetch(presign('GET', bucket, key));
			if (!r.ok) throw new Error(`${bucket}/${key}: ${r.status}`);
			await writeFile(dest, Buffer.from(await r.arrayBuffer()));
		}
		manifest.objects.push({ bucket, key });
		process.stdout.write(`   ${bucket}/${key}\n`);
	});
}

writeFileSync(path.join(out, 'manifest.json'), JSON.stringify(manifest, null, 1));
console.log(`\nexported ${manifest.stories.length} stories, ${manifest.drafts.length} drafts, ${manifest.images} images, ${manifest.objects.length} objects → ${out}`);
console.log('next: copy this folder to the droplet as /opt/fartingaround.fun/data/import and run scripts/migrate-import.mjs there (docs/deploy.md §2).');

// ── helpers ─────────────────────────────────────────────────────────────────

async function listBucket(bucket) {
	const keysOut = [];
	let token = null;
	do {
		const q = { 'list-type': '2', 'max-keys': '1000' };
		if (token) q['continuation-token'] = token;
		const r = await fetch(presign('GET', bucket, '', q));
		if (!r.ok) throw new Error(`list ${bucket}: ${r.status} ${await r.text()}`);
		const xml = await r.text();
		for (const m of xml.matchAll(/<Key>([^<]*)<\/Key>/g)) keysOut.push(decodeXml(m[1]));
		token = /<IsTruncated>true<\/IsTruncated>/.test(xml) ? decodeXml(xml.match(/<NextContinuationToken>([^<]*)</)?.[1] || '') : null;
	} while (token);
	return keysOut;
}

/** SigV4 presigned URL for R2 (region "auto"). */
function presign(method, bucket, key, extraQuery = {}) {
	const host = `${r2.accountId}.r2.cloudflarestorage.com`;
	const amzDate = new Date().toISOString().replace(/[:-]|\.\d{3}/g, '');
	const dateStamp = amzDate.slice(0, 8);
	const scope = `${dateStamp}/auto/s3/aws4_request`;
	const canonicalUri = '/' + enc(bucket) + (key ? '/' + key.split('/').map(enc).join('/') : '');
	const query = {
		...extraQuery,
		'X-Amz-Algorithm': 'AWS4-HMAC-SHA256',
		'X-Amz-Credential': `${r2.accessKeyId}/${scope}`,
		'X-Amz-Date': amzDate,
		'X-Amz-Expires': '3600',
		'X-Amz-SignedHeaders': 'host'
	};
	const canonicalQuery = Object.keys(query).sort().map((k) => `${enc(k)}=${enc(query[k])}`).join('&');
	const canonicalRequest = [method, canonicalUri, canonicalQuery, `host:${host}\n`, 'host', 'UNSIGNED-PAYLOAD'].join('\n');
	const stringToSign = ['AWS4-HMAC-SHA256', amzDate, scope, sha256(canonicalRequest)].join('\n');
	let k = createHmac('sha256', `AWS4${r2.secretAccessKey}`).update(dateStamp).digest();
	for (const part of ['auto', 's3', 'aws4_request']) k = createHmac('sha256', k).update(part).digest();
	const signature = createHmac('sha256', k).update(stringToSign).digest('hex');
	return `https://${host}${canonicalUri}?${canonicalQuery}&X-Amz-Signature=${signature}`;
}
// Function declarations (not const arrows): they're called by the top-level
// stage 3 code above, which runs before a `const` down here would initialise.
function sha256(s) {
	return createHash('sha256').update(s).digest('hex');
}
function enc(s) {
	return encodeURIComponent(s).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase());
}
function decodeXml(s) {
	return s.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'");
}

function extFromMime(mime, filename) {
	const m = mime.toLowerCase();
	if (m.includes('png')) return 'png';
	if (m.includes('webp')) return 'webp';
	if (m.includes('gif')) return 'gif';
	if (m.includes('avif')) return 'avif';
	if (m.includes('heic') || m.includes('heif')) return 'heic';
	if (m.includes('jpeg') || m.includes('jpg')) return 'jpg';
	const fromName = (filename || '').split('.').pop()?.toLowerCase();
	return fromName && fromName.length <= 4 ? fromName.replace('jpeg', 'jpg') : 'jpg';
}

function safeName(s) {
	return s.replace(/[^A-Za-z0-9._-]/g, '_');
}

async function pool(items, fn) {
	let i = 0;
	const workers = Array.from({ length: Math.min(CONCURRENCY, items.length) }, async () => {
		while (i < items.length) {
			const item = items[i++];
			await fn(item);
		}
	});
	await Promise.all(workers);
}
