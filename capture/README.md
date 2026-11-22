# fartingaround — native Android (Create / Consume)

The Android companion for [fartingaround.fun](https://fartingaround.fun),
Kotlin + Jetpack Compose. It replaces the Capacitor app that used to live in
`../app` (now deleted).

**Create** goes from rough notes to a published story without a laptop: share
photos in from any app (three or more become a photo dump), take photos and
video with the phone's own camera app, record audio — a standalone clip or a
*spoken caption* on a dump photo — jot notes, tags and a GPS drop with
weather + moon phase, then sync. The phone edits the same story JSON the web
editor at `/admin` does, through `/api/v1/stories` (see `../docs/api-v1.md`);
a draft is just a story with `draft: true`, and publishing is a switch.
Edits made on the site and on the phone are reconciled with a simple
"changed on the site — keep mine / take theirs" prompt.

**Consume** is an RSS/JSON Feed reader that renders `_fartingaround` stories
natively (photo dumps, galleries, maps, audio) and shows everything else as
an article. Subscriptions are OPML, backed up to the site when one is set.

## Why native

- **Uploads survive backgrounding.** Sync runs as a WorkManager foreground
  job, so a 300 MB video keeps going when you switch apps or lock the phone.
- **Share sheet.** Photos/videos/text shared from Google Photos, the camera
  app, a browser… land in a new draft.
- **Full-resolution media.** Photos and videos come from the system camera
  app via `TakePicture`/`CaptureVideo` into our private storage — the real
  original, with the phone's night mode / HDR — not the WebView's
  recompressed file-chooser copy. Audio is AAC `.m4a`.
- **Proper Nominatim User-Agent, proper permissions, no WebView.**

## Project layout

```
capture/
  build.gradle.kts, settings.gradle.kts, gradle/libs.versions.toml
  app/src/main/
    AndroidManifest.xml
    kotlin/fartingaround/capture/
      CaptureApp.kt          Application: hand-wired singletons, Coil loader
      MainActivity.kt        Compose host
      ShareActivity.kt       ACTION_SEND(_MULTIPLE) → new draft story (dump block)
      model/
        Types.kt             Coords, Weather/AstroSnapshot, SyncState, ids
        Story.kt             LocalStory (the doc as JsonObject + pending media)
        StoryDoc.kt          typed, lossless access to the Story JSON; block builders
      data/
        StoryStore.kt        one JSON file per story, exposed as a StateFlow;
                             one-time import of the old drafts/ folder
        BlobStore.kt         media bytes under filesDir/blobs
        Settings.kt          DataStore: api base, token, ElevenLabs, theme
        MediaInfo.kt         image dims (EXIF-aware), durations
      capture/
        AudioRecorder.kt     MediaRecorder → .m4a
        Location.kt          LocationManager fix + Nominatim reverse geocode
        Weather.kt           Open-Meteo
        Astro.kt             suncalc port: moon phase, sun events
        ImageDerivatives.kt  downscaled JPEGs for full-res R2 photos
      net/ApiClient.kt       OkHttp client for /api/v1 (stories + media), signed PUTs
      sync/
        SyncEngine.kt        media first (placeholders → ids), then POST/PUT with If-Match; 409 → conflict
        SyncWorker.kt        WorkManager foreground job per story
        Transcribe.kt        ElevenLabs Scribe, with word timestamps
      ui/
        AppNav.kt            top bar, tabs, NavHost
        theme/Theme.kt       paper, ink + night-vision palettes
        drafts/              StoriesListScreen (drafts first, pull from site)
        editor/              StoryEditorScreen, BlockCard, CaptureBar, players
        settings/, live/
```

Kotlin package is `fartingaround.capture` (not `fun.fartingaround.capture`)
because `fun` is a Kotlin keyword. The `applicationId` is still
`fun.fartingaround.capture`, so this installs over the Capacitor app.

## Storage model

Deliberately boring: drafts are JSON documents, one file each
(`filesDir/drafts/<id>.json`), loaded into memory on start and written
atomically on change. That's what the old app did with idb-keyval, and it
means no Room, no KSP, no migrations. Media lives as raw files in
`filesDir/blobs/` keyed by `localBlobId` until sync clears it.

## Building

Requires Android 12+ (minSdk 31). Open `capture/` in Android Studio (the Gradle wrapper is included; the first
sync downloads Gradle 8.13). Then **Run → Run 'app'** with your phone on USB.

If Android Studio flags a dependency version in `gradle/libs.versions.toml`
as unavailable, bump it to the nearest one it suggests — nothing here depends
on an exact number.

Release build: `Build → Generate Signed App Bundle / APK`, reusing the
`release.keystore` from the Capacitor setup (see `../app/SETUP.md` §9).

## First run

Settings tab → API base (`https://fartingaround.fun`) and the capture token
(`CAPTURE_API_TOKEN` on the site) → **Test connection**. Optionally an
ElevenLabs key for transcription.

Permissions are asked for in context: microphone on first Record, location on
first GPS tap, notifications when the first background sync starts. No camera
permission is needed — the system camera app takes the picture.

## Sync behaviour

Tap **Sync** on a story (or **Sync all**). Each story becomes one WorkManager
job with a progress notification; it needs a network connection and runs as a
foreground data-sync service so Android won't kill it mid-upload.

1. Every pending media file is uploaded and its `pending:<id>` placeholder in
   the document replaced with the real value:
   - photo → multipart `POST /api/v1/media/photo` → `imageId`, dimensions and
     private EXIF (time, GPS) land on the dump photo
   - audio / video → `POST /api/v1/media/sign` → `PUT` to the signed URL →
     `POST /api/v1/media/confirm` → the key lands on the block (or on the dump
     photo's `audio`). Audio bytes are kept on the phone for playback and
     transcription.
2. The document: `POST /api/v1/stories` the first time (the site picks the
   slug), afterwards `PUT /api/v1/stories/:slug` with `If-Match` set to the
   `updatedAt` this copy was based on. A 409 means the story was saved on the
   site in the meantime; the editor shows **Keep mine / Take the site's**.

A failure stops that story's sync and is written onto the story (red text in
the editor). Nothing retries by itself; fix the cause and tap Sync again.

**↓ From site** on the list pulls the site's drafts (including ones created in
`/admin` or migrated from the old drafts table) onto the phone; a local copy
with unsynced edits is never overwritten.

Not on the phone yet (edit on the site): diptychs, cover image + focus. Blocks
of those kinds can be reordered or removed here but not edited. Astro
galleries are retired: existing ones display and can be reordered or removed,
but can't be edited or added anywhere.

## Things the Capacitor app did that this doesn't

- Run in a laptop browser. It's an Android app now; iterate on a phone or the
  emulator.
