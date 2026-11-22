/*
 * media.als — Alloy 6 model of private media and the shared edge cache.
 * Companion to readers.als.
 *
 * Run 2026-11-11 in Alloy 6.2.0 (glucose, scope 3, 8 steps): the four
 * safety checks hold, NoLingeringCopies fails as intended, both runs are
 * consistent. This is the second draft; see "What changed" below.
 *
 * Design being checked (decided 2026-11-11):
 *  - Public media is served at /img and /media and may be edge-cached.
 *  - Private media is served only at /private/..., checked on every request,
 *    and is never stored in a shared cache. The viewer's own browser may
 *    keep it for a day (Cache-Control: private, max-age=86400).
 *  - The public paths serve a file ONLY while a published public story uses
 *    it. Everything else is "restricted" and is redirected to /private.
 *  - When a public story is made private or unpublished, copies already in
 *    the edge cache linger until they expire. Accepted and documented.
 *
 * What changed from the first draft: there, a file was restricted only if a
 * private story used it, and which story used which file never changed.
 * That hid a real hole: a photo uploaded into the editor is fetched (and
 * edge-cached for a year) BEFORE the story it belongs to is first saved as
 * private. Now `uses` can change over time and a file attached to nothing,
 * or only to drafts, is restricted from the moment it exists.
 *
 * Code: src/lib/media-access.js; tests: tests/media-access.test.js.
 * Not modelled: who the members are (readers.als) and feed tokens (the same
 * per-request rule).
 */
module media

abstract sig Visibility {}
one sig Public, Private extends Visibility {}

sig Media {}
sig Story {
  var uses: set Media,
  var vis: one Visibility
}
var sig Published in Story {}

var sig Cached in Media {}        -- a copy sits in the shared edge cache
var sig StrangerHas in Media {}   -- someone who is not a member has obtained a copy

-- The rule the public paths enforce on every request that reaches the server.
pred restricted[m: Media] {
  no s: uses.m | s in Published and s.vis = Public
}

pred sameStories { vis' = vis and Published' = Published and uses' = uses }
pred sameCopies  { Cached' = Cached and StrangerHas' = StrangerHas }

pred init {
  no Cached
  no StrangerHas
}

-- Anyone asks the server for a file on a public path. The server answers
-- only if the file is not restricted; the edge may keep a copy.
pred fetchPublic[m: Media] {
  not restricted[m]
  Cached' = Cached + m
  StrangerHas' = StrangerHas + m
  sameStories
}

-- Anyone asks for a file the edge already holds. The server is not consulted.
pred fetchCached[m: Media] {
  m in Cached
  StrangerHas' = StrangerHas + m
  Cached' = Cached
  sameStories
}

-- The edge copy expires.
pred evict[m: Media] {
  m in Cached
  Cached' = Cached - m
  StrangerHas' = StrangerHas
  sameStories
}

-- The author or a member fetching through /private changes nothing here:
-- the response is kept only in that person's own browser, never in the
-- shared cache, and no stranger gains a copy. It is the same as stutter.

-- The author adds a photo to a story, or takes one out.
pred attach[s: Story, m: Media] {
  uses' = uses + s -> m
  vis' = vis and Published' = Published and sameCopies
}
pred detach[s: Story, m: Media] {
  uses' = uses - s -> m
  vis' = vis and Published' = Published and sameCopies
}

pred setVis[s: Story, v: Visibility] {
  vis' = vis ++ s -> v
  Published' = Published and uses' = uses and sameCopies
}
pred publish[s: Story]   { Published' = Published + s and vis' = vis and uses' = uses and sameCopies }
pred unpublish[s: Story] { Published' = Published - s and vis' = vis and uses' = uses and sameCopies }
pred stutter { sameStories and sameCopies }

fact traces {
  init
  always (
    stutter
    or (some m: Media | fetchPublic[m] or fetchCached[m] or evict[m])
    or (some s: Story, m: Media | attach[s, m] or detach[s, m])
    or (some s: Story, v: Visibility | setVis[s, v])
    or (some s: Story | publish[s] or unpublish[s])
  )
}

---------------------------------------------------------------- properties

-- A stranger only ever holds a file that was, at some moment, not restricted.
assert StrangersOnlyGetOncePublic {
  always all m: Media | m in StrangerHas implies once not restricted[m]
}

-- A file that is restricted for its whole life never reaches the cache or a stranger.
assert AlwaysPrivateNeverLeaves {
  all m: Media | (always restricted[m]) implies always (m not in Cached and m not in StrangerHas)
}

-- The hole the first draft hid: a photo that is only ever attached to
-- private stories or drafts, however it got there, never leaves.
assert NeverInAPublishedPublicStoryNeverLeaves {
  all m: Media |
    (always no s: uses.m | s in Published and s.vis = Public)
      implies always (m not in Cached and m not in StrangerHas)
}

-- Once a restricted file is out of the cache, the server never puts it back
-- while it stays restricted. So the lingering window ends at expiry.
assert ServerNeverRefillsCache {
  always all m: Media |
    (restricted[m] and m not in Cached) implies after (restricted[m] implies m not in Cached)
}

-- EXPECTED TO FAIL. This is the accepted lingering window: a file cached
-- while public is still in the cache right after its story goes private.
-- The counterexample is the scenario the publish warning describes.
assert NoLingeringCopies {
  always all m: Media | restricted[m] implies m not in Cached
}

check StrangersOnlyGetOncePublic              for 3 but 8 steps
check AlwaysPrivateNeverLeaves                for 3 but 8 steps
check NeverInAPublishedPublicStoryNeverLeaves for 3 but 8 steps
check ServerNeverRefillsCache                 for 3 but 8 steps
check NoLingeringCopies                       for 3 but 8 steps   -- expect a counterexample

-- Sanity: a photo that starts out restricted can become public by being
-- published in a public story.
run canBecomePublic {
  some m: Media | restricted[m] and eventually m in StrangerHas
} for 3 but 8 steps

-- Sanity: the editor scenario. A photo exists, is attached to a story that
-- is private from the start, and a stranger never gets it. (The check above
-- proves "never"; this shows the scenario itself is possible in the model.)
run uploadedIntoPrivateStory {
  some m: Media, s: Story {
    no uses.m
    s.vis = Private
    eventually (m in s.uses and s in Published)
  }
} for 3 but 8 steps

/*
 * Things to poke at together:
 * 1. Decided while building (2026-11-11): drafts' media is restricted too.
 *    The phone app therefore sends its token when loading images.
 * 2. Detaching a photo from its last public story restricts it. Same
 *    lingering window as a flip to private.
 * 3. A "never cache /private" mistake by an installer is outside the model.
 */
