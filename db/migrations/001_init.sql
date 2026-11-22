-- Initial schema. Written in the subset of SQL that SQLite and Postgres share:
-- TEXT / INTEGER only, ISO-8601 strings for timestamps, 0/1 for booleans, and
-- each document stored whole as JSON text with the few columns we filter or
-- sort on pulled out beside it. Applied by src/lib/server/db.js at startup.

CREATE TABLE IF NOT EXISTS stories (
  slug        TEXT PRIMARY KEY,
  date        TEXT NOT NULL,            -- yyyy-mm-dd, the story's own date
  draft       INTEGER NOT NULL DEFAULT 0,
  updated_at  TEXT NOT NULL,
  doc         TEXT NOT NULL             -- the full Story as JSON
);
CREATE INDEX IF NOT EXISTS stories_date ON stories (date DESC, updated_at DESC);

CREATE TABLE IF NOT EXISTS drafts (
  id          TEXT PRIMARY KEY,
  status      TEXT NOT NULL,            -- open | promoted | archived
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  doc         TEXT NOT NULL             -- the full Draft as JSON
);
CREATE INDEX IF NOT EXISTS drafts_created ON drafts (created_at DESC);

-- Uploaded images (what Cloudflare Images used to track for us). The bytes
-- live at objects/images/<id>/orig.<ext>; transformed variants are cached
-- next to them. Audio/video objects are not tracked here — their keys live on
-- the draft/story documents, same as before.
CREATE TABLE IF NOT EXISTS images (
  id          TEXT PRIMARY KEY,
  ext         TEXT NOT NULL,
  mime        TEXT NOT NULL,
  width       INTEGER,
  height      INTEGER,
  bytes       INTEGER NOT NULL,
  filename    TEXT,
  created_at  TEXT NOT NULL
);
