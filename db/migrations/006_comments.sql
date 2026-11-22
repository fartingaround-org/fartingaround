-- Comments on stories, by invited readers and by the owner.
-- One level of threading: a reply's parent is always a top-level comment.
-- Removing a contact keeps their comments' rows (contact_id becomes NULL)
-- unless the owner chose to delete them; deleting a story deletes its comments.
CREATE TABLE IF NOT EXISTS comments (
  id          TEXT PRIMARY KEY,
  story_slug  TEXT NOT NULL REFERENCES stories (slug) ON DELETE CASCADE,
  contact_id  TEXT REFERENCES contacts (id) ON DELETE SET NULL,
  by_owner    INTEGER NOT NULL DEFAULT 0,          -- 1 = written by the site's owner
  parent_id   TEXT REFERENCES comments (id) ON DELETE CASCADE,
  body        TEXT NOT NULL,
  status      TEXT NOT NULL DEFAULT 'visible',     -- visible | hidden | deleted
  created_at  TEXT NOT NULL,
  edited_at   TEXT
);
CREATE INDEX IF NOT EXISTS comments_story ON comments (story_slug, created_at);
CREATE INDEX IF NOT EXISTS comments_contact ON comments (contact_id, created_at);
