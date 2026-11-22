package cmd

import (
	"os"
	"path/filepath"
	"strings"
)

func init() {
	register(&command{name: "disk", summary: "what's using the data volume: photos, rendered variants, audio, video, drafts, database, leftovers", run: runDisk})
}

// runDisk breaks the data path down the way you'd want to when it's full.
// Variants are the on-demand image renders (safe to delete — they come back);
// originals and the database are the site.
func runDisk(c *Ctx, _ []string) error {
	dp := c.In.DataPath()
	if free, total, err := diskFree(dp); err == nil {
		say("%s: %s free of %s", dp, humanBytes(free), humanBytes(total))
	}
	var originals, variants, audio, video, gallery, drafts, db, other uint64
	var nOrig, nVar, nAudio, nVideo, nGallery, nDraft int
	entries, err := os.ReadDir(dp)
	if err != nil {
		return err
	}
	leftovers := []string{}
	for _, e := range entries {
		p := filepath.Join(dp, e.Name())
		switch {
		case e.Name() == "objects":
			filepath.WalkDir(p, func(path string, d os.DirEntry, err error) error {
				if err != nil || d.IsDir() {
					return nil
				}
				fi, err := d.Info()
				if err != nil {
					return nil
				}
				sz := uint64(fi.Size())
				rel, _ := filepath.Rel(p, path)
				switch {
				case strings.HasPrefix(rel, "images/") && strings.HasPrefix(filepath.Base(path), "orig."):
					originals += sz
					nOrig++
				case strings.HasPrefix(rel, "images/"):
					variants += sz
					nVar++
				// Published clips are keyed audio/<id>.<ext> and video/<id>.<ext>
				// (storyMediaKey in objects.js), so the folder says which is which.
				case strings.HasPrefix(rel, "audio/"):
					audio += sz
					nAudio++
				case strings.HasPrefix(rel, "video/"):
					video += sz
					nVideo++
				// Clips uploaded through the draft editor keep their
				// drafts/<draftId>/<attachmentId>.<ext> key after the draft is
				// published (promote copies the key into the story block), so
				// this folder holds published media too. Sort it by extension;
				// .webm can be either, so it stays on the drafts line.
				case strings.HasPrefix(rel, "drafts/"):
					switch strings.ToLower(filepath.Ext(path)) {
					case ".m4a", ".mp3", ".ogg", ".wav":
						audio += sz
						nAudio++
					case ".mp4", ".mov":
						video += sz
						nVideo++
					default:
						drafts += sz
						nDraft++
					}
				// Full-resolution gallery uploads: gallery/<id>/orig.<ext> plus
				// the uploaded w<width>.jpg sizes. Not a cache; they don't regenerate.
				case strings.HasPrefix(rel, "gallery/"):
					gallery += sz
					nGallery++
				default:
					other += sz
				}
				return nil
			})
		case strings.HasPrefix(e.Name(), "site.db"):
			if fi, err := e.Info(); err == nil {
				db += uint64(fi.Size())
			}
		case e.Name() == "backups":
			_, sz := dirStats(p)
			say("  backups      %10s", humanBytes(sz))
		default:
			_, sz := dirStats(p)
			other += sz
			if sz > 50<<20 {
				leftovers = append(leftovers, e.Name()+" ("+humanBytes(sz)+")")
			}
		}
	}
	say("  originals    %10s  %d photos", humanBytes(originals), nOrig)
	say("  variants     %10s  %d rendered sizes (cache; regenerates on demand)", humanBytes(variants), nVar)
	say("  audio        %10s  %d clips (published and in drafts)", humanBytes(audio), nAudio)
	say("  video        %10s  %d clips (published and in drafts)", humanBytes(video), nVideo)
	if nGallery > 0 {
		say("  gallery      %10s  %d full-resolution files (uploaded; not a cache)", humanBytes(gallery), nGallery)
	}
	if nDraft > 0 {
		say("  drafts       %10s  %d other draft-editor uploads (.webm and unknown types)", humanBytes(drafts), nDraft)
	}
	say("  database     %10s", humanBytes(db))
	say("  other        %10s", humanBytes(other))
	for _, l := range leftovers {
		warn("%s — not something the site uses; e.g. a finished migration import. Delete it if you're done with it.", l)
	}
	if variants > originals {
		warn("rendered variants outweigh the originals; the width ladder may be bigger than this site needs")
	}
	return nil
}
