-- Who this site's author follows: the reader app's subscription list, backed
-- up as OPML via PUT /api/v1/following and published as the blogroll at
-- /following (+ /following.opml). One row.
CREATE TABLE IF NOT EXISTS following (
  id          INTEGER PRIMARY KEY,      -- always 1
  opml        TEXT NOT NULL,
  updated_at  TEXT NOT NULL
);
