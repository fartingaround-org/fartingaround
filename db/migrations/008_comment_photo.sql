-- A comment can point at one photo of the story it is on (a photo-dump
-- photo, by image id). NULL for an ordinary comment. Whether the pointer is
-- still shown is decided when comments are read: only while that photo is
-- in the story (see listComments).
ALTER TABLE comments ADD COLUMN photo_id TEXT;
