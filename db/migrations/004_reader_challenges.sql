-- One-time WebAuthn challenges. The browser holds only the row id (in a
-- short-lived cookie); the challenge itself stays on the server and is
-- deleted when it is used, so a captured passkey response cannot be replayed.
CREATE TABLE IF NOT EXISTS reader_challenges (
  id          TEXT PRIMARY KEY,
  purpose     TEXT NOT NULL,                       -- register | signin
  contact_id  TEXT REFERENCES contacts (id) ON DELETE CASCADE,
  challenge   TEXT NOT NULL,
  expires_at  TEXT NOT NULL
);
