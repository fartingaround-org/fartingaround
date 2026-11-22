-- Admin sign-in sessions. The admin cookie used to hold the password itself;
-- it now holds a random token whose hash is stored here, so a leaked cookie
-- is not the password and logging out really ends the session.
CREATE TABLE IF NOT EXISTS admin_sessions (
  token_hash  TEXT PRIMARY KEY,
  created_at  TEXT NOT NULL,
  expires_at  TEXT NOT NULL
);
