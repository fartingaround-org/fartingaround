-- Small key/value store for site-wide choices the owner makes in the admin
-- (first use: whether new stories start public or private).
CREATE TABLE IF NOT EXISTS site_settings (
  key         TEXT PRIMARY KEY,
  value       TEXT NOT NULL,
  updated_at  TEXT NOT NULL
);
