# `/api/v1` — authoring API

How a client (the Kotlin app, a script, anything) creates, edits and publishes
stories on an instance. Stable within v1: fields are added, never renamed or
removed. Public reading is elsewhere: `feed.json`, `rss.xml`, `/api/stories`,
and the media URLs in `feed-extension.md`.

**Auth.** `Authorization: Bearer <CAPTURE_API_TOKEN>` on every request. The
admin cookie also works same-origin (the web editor uses the same lib
functions through its own routes). All endpoints answer `OPTIONS` with
permissive CORS; the bearer token is the gate.

**Errors.** `{ "error": "<snake_case_code>", ...detail }` with a fitting status.
Codes worth switching on: `unauthorized`, `story_not_found`, `conflict`,
`no_base`, `slug_taken`, `object_not_found`, `transcription_disabled`.

## The document

A story is the `Story` type in `src/lib/types.js` — the same JSON the
`_fartingaround` feed extension carries, plus author-only fields the feed
strips: `draft`, `DumpPhoto.exif`, `DumpPhoto.audio.key`, `r2Key` on audio and
video blocks. **A draft is a story with `draft: true`.** Publishing is saving
it with `draft: false`. There is no separate draft type.

The server owns `createdAt` and `updatedAt`; whatever you send for them is
ignored. `updatedAt` is also the concurrency token (below).

Blocks need a `type` (one of the known types) and an `id`; an `id` is minted if
you leave it off, but mint your own so that media you upload can be linked to
the block before the first save. Everything else inside a block is passed
through unchanged.

## Stories

```
GET    /api/v1/stories[?draft=1|0]      { stories: [summary…] }
POST   /api/v1/stories                  201 { story }
GET    /api/v1/stories/:slug            { story }           ETag: "<updatedAt>"
PUT    /api/v1/stories/:slug            200|201 { story }   ETag: "<updatedAt>"
DELETE /api/v1/stories/:slug            204
```

- **List** returns summaries (`slug, title, date, location, summary,
  coverImageId, tags, draft, visibility, createdAt, updatedAt, blockCount`). `draft=1`
  sorts by `updatedAt` desc (what you were just working on); otherwise by
  story date desc.
- **`visibility`** is `"public"` or `"private"` (members only; see
  `src/lib/story-access.js`). Absent on a stored story means public. Leave it
  out of a write and the server keeps the stored value, or applies the
  owner's site default to a new story; it is never changed by omission, so a
  client that doesn't know the field can't make a private story public.
- **POST** creates. Send a story without `slug` and one is made from the
  title and de-duplicated (`iceland-2026`, `iceland-2026-2`, …); send a `slug`
  and it's honoured or refused with 409 `slug_taken`. `draft` defaults to
  `true`.
- **PUT** creates or replaces. It is **conditional**: send the `updatedAt`
  your copy was based on, as `If-Match: "<updatedAt>"` or `baseUpdatedAt` in
  the body.
  - Matches → saved, 200, new `updatedAt` in the body and ETag.
  - Doesn't match → **409 `conflict`** with `current` (the server's story).
    Nothing was written. Offer keep-mine (PUT again with `If-Match` set to
    `current.updatedAt`) or take-theirs (replace your local copy with
    `current`).
  - Existing story and no base at all → 409 `no_base`, unless `?force=1`.
  - Slug not taken → created, 201; no base needed.
- **DELETE** removes the story **and every file it referenced** (images,
  clips, full-res originals). There is no undo.

## Media

Files are uploaded on their own and then referenced from blocks by id/key;
the story PUT is what "registers" them. Upload first, then save the story,
so a saved story never points at a missing file.

### Photos (the common case)

```
POST /api/v1/media/photo        multipart/form-data: file        ≤ 50 MB
→ 201 { imageId, w, h, takenAt?, exif? }
```

Drop the result straight into a `DumpPhoto` (`{ imageId, w, h, takenAt, exif,
caption?, alt? }`) or use `imageId` in a `photo`/`diptych`/`gallery` block or
as `coverImageId`. `exif` is `{ lat?, lon?, takenAt? }` read from the file —
**private**; keep it on the photo and leave `coords` unset until the author
applies the location. Image URLs are `{base}/img/{imageId}/{params}` as in the
feed spec.

### Audio and video (signed PUT)

```
POST /api/v1/media/sign
  { kind: "audio" | "video", mimeType, id? }
  → { id, key, url, expiresAt }

PUT  <url>                       raw bytes, Content-Type set, no auth header

POST /api/v1/media/confirm      { key } | { keys: [..] }
  → { objects: [{ key, size }] }      409 object_not_found { missing }
```

URLs are good for 15 minutes and may point at a different host
(`UPLOAD_ORIGIN`) than the API — follow them as given. Pass your own `id`
(≥ 6 url-safe chars) so a retried upload overwrites rather than strays.

Where the result goes:

| upload | block field |
|---|---|
| `audio` | `audio` block: `{ type, id: <id>, r2Key: <key>, mimeType, durationMs?, caption?, transcript? }` |
| `audio` as a dump photo's spoken caption | `DumpPhoto.audio: { id, key, mimeType, durationMs?, transcript?, words? }` |
| `video` | `video` block: `{ type, id: <id>, r2Key: <key>, mimeType, durationMs?, posterImageId?, caption? }` |

`kind: "photo-full"` (full-resolution photos for the astrophotography
gallery) was withdrawn 2026-11: galleries are display-only and can no longer
be created. It now answers 400 `bad_kind`.

Use the signed `id` as the block id for audio/video: the public playback URL
is `/api/stories/:slug/audio/:id` (and `/video/:id`), resolved from the block
or from a dump photo's `audio.id`.

### Transcription (optional)

```
GET  /api/v1/media/transcribe          { enabled, model }
POST /api/v1/media/transcribe { key }  { transcript, words: [{ t, s, e }], model, languageCode? }
```

Server-side ElevenLabs Scribe with word timestamps; 503
`transcription_disabled` when the instance has no `ELEVENLABS_API_KEY`. A
client with its own key can call ElevenLabs directly instead — same `words`
shape (`t` word, `s`/`e` seconds).

## Comments

`GET /api/v1/stories/:slug/comments` → `{ enabled, canPost, as, comments }`.
Unlike the rest of this API it is not owner-only: the public, a reader (their
cookie, or a reader token as `?token=…` or a bearer) and the owner each get
what the story page would show them. A story the asker cannot read is a 404;
`enabled: false` with no comments means there is no comment section for them
(a draft, comments off, or readers-only comments and no reader).

`comments` is oldest first, each `{ id, name, byOwner, body, createdAt,
editedAt, deleted, photoId, replies }`. `photoId` is the image id of the
photo-dump photo the comment is about, or null; a reply carries its
parent's.

`POST /api/v1/stories/:slug/comments` with `{ body, parentId?, photoId? }` →
`201 { id }`. Owner only (admin cookie or the bearer token); a reader token
gets `403 owner_only`, because a feed address can leak and must never be
able to write. Errors are `400 { error, message }` with `error` one of
`empty`, `too_long`, `too_many_links`, `too_fast`, `bad_parent`, `bad_photo`.

## Site settings

`GET /api/v1/settings` → `{ authorName }`; `PUT /api/v1/settings` with
`{ authorName }` → the same. Owner only. `authorName` is the name the owner's
comments are signed with; `''` means none chosen (comments read "Author").

## A typical phone flow

1. Share 12 photos → `POST /media/photo` ×12 (in parallel, a few at a time) →
   build a `dump` block from the results, in `takenAt` order.
2. `POST /api/v1/stories { title, date, location, coords, weather, astro,
   blocks: [dump], draft: true }` → 201; keep `slug` and `updatedAt`.
3. Record a note → `sign` → PUT → `confirm` → append an `audio` block →
   `PUT /api/v1/stories/:slug` with `If-Match`.
4. Edit on the laptop; the web editor saves with the same conditional
   check, so whichever side is stale gets asked.
5. Publish: `PUT` with `draft: false`.

Offline: keep the story JSON and the blobs locally; blocks may carry a
client-only placeholder (e.g. `localBlobId`) until the upload succeeds and
you substitute the real `imageId`/`key` before the PUT. The server will store
unknown block fields as-is, so strip placeholders before sending.
