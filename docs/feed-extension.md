# The `_fartingaround` feed extension

**Version 1.** How a fartingaround-style site publishes its block-based
stories inside an ordinary [JSON Feed 1.1](https://jsonfeed.org/version/1.1),
so that a reader which understands the extension can render stories natively
— photo dumps as grids with a lightbox, galleries with their layers, audio
with a player, map paths — while every other reader sees normal HTML.

This is the only "protocol" between sites. There is no registry, no handshake
and no central anything: a reader subscribes to `https://example.org/feed.json`
like any feed, notices the extension, and does more with it.

## Discovery

The site's pages link both feeds:

```html
<link rel="alternate" type="application/rss+xml"  href="/rss.xml">
<link rel="alternate" type="application/feed+json" href="/feed.json">
```

A reader given a site URL should prefer `feed.json` when present. `rss.xml`
carries the same stories with full HTML in `content:encoded` but no blocks.

## Feed-level

```json
{
  "version": "https://jsonfeed.org/version/1.1",
  "title": "…", "home_page_url": "…", "feed_url": "…/feed.json",
  "_fartingaround": {
    "version": 1,
    "base": "https://example.org",
    "spec": "https://github.com/fartingaround-org/fartingaround/blob/main/docs/feed-extension.md"
  },
  "items": [ … ]
}
```

- `version` — integer; incremented only for incompatible changes. A reader
  that sees a higher version than it knows should fall back to `content_html`.
- `base` — the origin every media URL below is built against. Always the
  site's own origin; never trust a `base` on a different host.

## Item-level

Standard JSON Feed fields are all filled (`id`, `url`, `title` when the
story has one,
`content_html`, `summary`, `image`, `date_published`, `date_modified`,
`tags`). The extension adds:

```json
"_fartingaround": {
  "slug": "iceland-2026",
  "date": "2026-05-14",
  "location": "Vík, Iceland",
  "coords": { "lat": 63.4187, "lon": -19.0061 },
  "weather": { "tempC": 9, "tempF": 48, "conditions": "Overcast", "source": "open-meteo", "fetchedAt": "…" },
  "astro": { "moonPhase": 0.44, "moonPhaseLabel": "Waxing gibbous", "moonIllumination": 0.96, "sunrise": "…", "sunset": "…" },
  "coverImageId": "…",
  "coverFocus": { "x": 0.5, "y": 0.3 },
  "blocks": [ … ]
}
```

Everything except `slug`, `date` and `blocks` is optional.

### Items without a title (notes)

A story does not need a title. Such an item has **no `title`** in the JSON
Feed (and no `<title>` in the RSS feed); everything else is unchanged. An
aware reader should treat it as a note: no headline, no cover, and its first
block shown where the item is listed instead of a row to tap through. A title
of exactly "Untitled" from an older site means the same thing.

## Blocks

`blocks` is the story's content in order. Each block has `type` and `id`.
Unknown block types must be skipped, not treated as errors. The types and
their fields match `src/lib/types.js` in the reference implementation:

| type | fields |
|---|---|
| `text` | `body` — plain prose; blank line = paragraph; `[text](url)` and bare URLs are links |
| `photo` | `imageId`, `caption?`, `alt?`, `size?` (`inline`/`wide`/`full`), `aspect?` (`"4/5"`) |
| `diptych` | `left`, `right` (each `{ imageId, caption?, alt? }`), `caption?`, `aspect?` |
| `gallery` | `items[]` of `{ imageId? \| r2?, annotationId?, overlayId?, starlessId?, caption?, alt? }`, `caption?`, `size?`, `aspect?` — the astrophotography gallery. **Retired 2026-11:** instances no longer create or edit these; existing ones are still emitted, so readers should keep displaying them (or fall back to `content_html`). `r2` is a full-resolution photo (see Media) |
| `dump` | `photos[]` of `{ imageId, w?, h?, caption?, alt?, takenAt?, coords?, audio?, section? }`, `caption?`, `size?`, `rowSize?`, `slideshowSeconds?`, `map?` (`none`/`pins`/`path`) — a photo dump |
| `pull` | `body` |
| `aside` | `body`, `label?` |
| `divider` | `place?`, `date?` — a postmark between parts of a trip; both are a few words at most |
| `heading` | `body` — a section title, one line of plain text (added 2026-11) |
| `code` | `body`, `caption?` — text to show exactly as written, in a fixed-width face: a command, a snippet. No links, no reflowing (added 2026-11) |
| `audio` | `mimeType`, `durationMs?`, `caption?`, `transcript?` |
| `video` | `mimeType`, `durationMs?`, `caption?`, `posterImageId?`, `aspect?` |

`dump.photos[].coords` is present only for photos whose author chose to
publish the location; `takenAt` orders the map path.

`dump.photos[].section`, when present, is the title of a section that starts
at that photo: show a heading before it. Optional and additive; a reader that
ignores it shows the dump as one run of photos.

`dump.photos[].audio` is a spoken caption:
`{ id, mimeType, durationMs?, transcript?, words? }`, where `words` is
`[{ t, s, e }]` — the word, and its start/end in seconds — when the clip was
transcribed with timestamps. Playback URL is under Media URLs below.

## Media URLs

Readers build URLs from `base`; ids are opaque and never change.

**Images** (`imageId`, `coverImageId`, `annotationId`, `overlayId`,
`starlessId`, `posterImageId`):

```
{base}/img/{imageId}/{params}
```

`params` is a comma-separated list of `w=` (width, px), `h=`, `q=` (quality
40–95), `fit=` (`cover` | `contain` | `scale-down`), `gravity=XxY` (focal
point 0–1, with `fit=cover`), `format=auto` (negotiates avif/webp; needed for
transparent overlays). Widths snap to a fixed ladder up to 2400; ask for what
you need and accept what comes. `{base}/img/{imageId}/public` is the full
image at 2000px. Examples:

```
{base}/img/{id}/w=720,q=82                      a thumbnail
{base}/img/{id}/w=2000,q=88,fit=scale-down       lightbox
{base}/img/{id}/w=1200,h=675,fit=cover,gravity=0.5x0.3   cover crop
```

**Full-resolution gallery photos** (`gallery.items[].r2`):
`{ base, widths[], ext, w, h }` →

```
{base}/media/{r2.base}/w{width}.jpg      one of r2.widths
{base}/media/{r2.base}/orig.{r2.ext}     the untouched original
```

**Audio / video** (block `id`, or a dump photo's `audio.id`):

```
{base}/api/stories/{slug}/audio/{blockId | audio.id}
{base}/api/stories/{slug}/video/{blockId}
```

Both support HTTP Range.

### Private stories and personal feeds

A site may give an invited member a personal feed URL carrying a token:
`/feed.json?token=<token>` (and `/rss.xml?token=<token>`). That feed also
lists the private stories the member may read; such an item has
`_fartingaround.private: true`.

- In `content_html` and `image`, media URLs of private items already point at
  `/private/img/…` and `/private/media/…` and carry `?token=<token>`.
- A reader that builds its own media URLs from the blocks must, for a private
  item, append `?token=<the token from the feed URL>` to every `/img/…`,
  `/media/…` and `/api/stories/…` URL (or send it as `Authorization: Bearer`).
  The public addresses redirect to `/private/…` and keep the query string.
- Treat the feed URL as a secret. Do not log it, show it in share sheets, or
  send it to any host but the feed's own.
- A token that has been replaced or revoked makes the feed answer 404.

## Rendering guidance (non-normative)

- `content_html` is the fallback and is always complete; a reader can show it
  and progressively replace blocks it understands.
- A `dump` with `map: "path"` and ≥ 2 photos with `coords` is a route in
  `takenAt` order; `pins` is unordered markers.
- `gallery` items with `overlayId`/`annotationId`/`starlessId` are
  astrophotography layers: overlay = transparent labels to composite over the
  photo; annotation = a complete labelled image; starless = alternate base.
- `astro.moonPhase` is 0–1 (0/1 new, 0.5 full).
- A dump photo with `audio`: in a slideshow, play the clip first and start
  the per-photo timer when it ends. While it plays, `words` can be shown
  closed-caption style (light each word up between `s` and `e`); afterwards
  show `caption` (or `transcript` if there's no caption).

## Compatibility promise

Fields are only added, never renamed or removed, within a version. New block
types may appear at any time. Readers should ignore what they don't know.
