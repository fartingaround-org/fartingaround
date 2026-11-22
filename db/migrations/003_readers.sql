-- Readers: the people an owner has let in. See specs/comments-and-accounts-plan.md
-- and docs/models/readers.als (the Alloy model these tables are checked against).
--
-- A contact gets in by redeeming a single-use link the owner shared, then
-- signs in with a passkey. No passwords. Removing a contact deletes the row
-- and, through the cascades, everything they held.

CREATE TABLE IF NOT EXISTS contacts (
  id            TEXT PRIMARY KEY,
  display_name  TEXT NOT NULL,
  note          TEXT NOT NULL DEFAULT '',          -- owner's private note
  email         TEXT,
  role          TEXT NOT NULL DEFAULT 'commenter', -- member | commenter
  status        TEXT NOT NULL DEFAULT 'invited',   -- invited | active | banned
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL,
  last_seen_at  TEXT
);

-- Sign-in links (invite and recovery are the same thing) and add-device
-- links. Only the hash of the code is stored. A link is usable while
-- used_at and revoked_at are both NULL and expires_at is in the future.
CREATE TABLE IF NOT EXISTS reader_links (
  id          TEXT PRIMARY KEY,
  contact_id  TEXT NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
  purpose     TEXT NOT NULL,                       -- signin | add_device
  code_hash   TEXT NOT NULL UNIQUE,
  created_at  TEXT NOT NULL,
  expires_at  TEXT NOT NULL,
  used_at     TEXT,
  revoked_at  TEXT
);
CREATE INDEX IF NOT EXISTS reader_links_contact ON reader_links (contact_id);
-- Model check OneOutstandingLink, enforced by the database as well.
CREATE UNIQUE INDEX IF NOT EXISTS reader_links_one_unused_signin
  ON reader_links (contact_id)
  WHERE purpose = 'signin' AND used_at IS NULL AND revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS reader_sessions (
  id          TEXT PRIMARY KEY,
  contact_id  TEXT NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
  token_hash  TEXT NOT NULL UNIQUE,
  created_at  TEXT NOT NULL,
  expires_at  TEXT NOT NULL,
  user_agent  TEXT
);
CREATE INDEX IF NOT EXISTS reader_sessions_contact ON reader_sessions (contact_id);

-- WebAuthn credentials. Created now so revocation is complete from day one;
-- registration and sign-in arrive with the passkey step.
CREATE TABLE IF NOT EXISTS reader_passkeys (
  id             TEXT PRIMARY KEY,
  contact_id     TEXT NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
  credential_id  TEXT NOT NULL UNIQUE,
  public_key     TEXT NOT NULL,
  sign_count     INTEGER NOT NULL DEFAULT 0,
  transports     TEXT,
  label          TEXT NOT NULL DEFAULT '',
  created_at     TEXT NOT NULL,
  last_used_at   TEXT
);
CREATE INDEX IF NOT EXISTS reader_passkeys_contact ON reader_passkeys (contact_id);

-- Per-contact token for the private feed URL and the phone app.
CREATE TABLE IF NOT EXISTS reader_tokens (
  id          TEXT PRIMARY KEY,
  contact_id  TEXT NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
  token_hash  TEXT NOT NULL UNIQUE,
  created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS reader_tokens_contact ON reader_tokens (contact_id);
