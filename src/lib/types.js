/**
 * Story data model.
 *
 * Stored in KV under key `story:<slug>`, with an index list at `index:stories`
 * holding the ordered list of slugs (newest first).
 *
 * @typedef {Object} TextBlock
 * @property {'text'} type
 * @property {string} id
 * @property {string} body                Plain prose. Newlines become paragraph breaks at render time. Links: `[text](url)` or bare URLs become anchors.
 *
 * @typedef {Object} PhotoBlock
 * @property {'photo'} type
 * @property {string} id
 * @property {string} imageId             Cloudflare Images ID
 * @property {string} [caption]
 * @property {string} [alt]
 * @property {'inline'|'wide'|'full'} [size]
 * @property {string} [aspect]            e.g. "4/5", "3/2"
 *
 * @typedef {Object} DiptychBlock
 * @property {'diptych'} type
 * @property {string} id
 * @property {{imageId: string, caption?: string, alt?: string}} left
 * @property {{imageId: string, caption?: string, alt?: string}} right
 * @property {string} [caption]
 * @property {'wide'|'full'} [size]
 * @property {string} [aspect]
 *
 * @typedef {Object} R2Image
 * @property {string} base                R2 key prefix, e.g. "gallery/<id>". The
 *                                        original lives at `<base>/orig.<ext>` and
 *                                        each derivative at `<base>/w<width>.jpg`,
 *                                        all served publicly via /media/<key>.
 * @property {number[]} widths            Derivative widths present, ascending.
 * @property {string} ext                 Original file extension (no dot).
 * @property {number} w                   Original intrinsic width (px).
 * @property {number} h                   Original intrinsic height (px).
 *
 * @typedef {Object} GalleryItem
 * @property {string} [imageId]           Cloudflare Images ID (main photo). Used for
 *                                        ordinary photos; mutually exclusive with r2.
 * @property {R2Image} [r2]               Full-resolution main photo stored in R2 with
 *                                        pre-generated derivatives — for astrophotos
 *                                        that blow past the Cloudflare Images 10 MB cap.
 * @property {string} [annotationId]      Optional plate-solved star map — a complete
 *                                        annotated image (labels baked in) shown on hover
 *                                        and as a base layer. A Cloudflare Images ID.
 * @property {string} [overlayId]         Optional annotation OVERLAY — a transparent PNG
 *                                        of just the labels/grid, composited on top of
 *                                        whichever base is showing (photo or starless).
 *                                        Much lighter than a full annotated image, so
 *                                        prefer this for new astrophotos. Cloudflare
 *                                        Images ID; transparency is preserved on delivery.
 * @property {string} [starlessId]        Optional "nebula only" / no-stars rendering
 *                                        (e.g. a StarXTerminator export). Selectable as an
 *                                        alternate base layer. A Cloudflare Images ID.
 * @property {string} [caption]
 * @property {string} [alt]
 *
 * @typedef {Object} GalleryBlock          Astrophotography gallery — a filmstrip of
 *                                        photos, each with optional annotation /
 *                                        starless layers. For an ordinary trip
 *                                        photo dump use DumpBlock instead.
 * @property {'gallery'} type
 * @property {string} id
 * @property {GalleryItem[]} items
 * @property {string} [caption]           Caption for the whole gallery
 * @property {'inline'|'wide'|'full'} [size]
 * @property {string} [aspect]            e.g. "3/2", "4/5" — frame ratio for the filmstrip
 *
 * @typedef {Object} TranscriptWord
 * @property {string} t                   The word
 * @property {number} s                   Start, seconds into the clip
 * @property {number} e                   End, seconds
 *
 * @typedef {Object} DumpPhotoAudio       A spoken caption for one dump photo.
 * @property {string} id                  Opaque, stable. Public playback is
 *                                        /api/stories/:slug/audio/:id (the same
 *                                        route audio blocks use).
 * @property {string} key                 Object-store key, `audio/<id>.<ext>`.
 *                                        PRIVATE — stripped by publicStory().
 * @property {string} mimeType
 * @property {number} [durationMs]
 * @property {string} [transcript]        Plain text
 * @property {TranscriptWord[]} [words]   Word timings (ElevenLabs Scribe), for
 *                                        closed-caption style highlighting
 * @property {string} [transcriptModel]
 *
 * @typedef {Object} DumpPhotoExif
 * @property {number} [lat]               GPS from the original file, if it had any
 * @property {number} [lon]
 * @property {string} [takenAt]           ISO DateTimeOriginal
 *
 * @typedef {Object} DumpPhoto
 * @property {string} imageId             Cloudflare Images ID
 * @property {number} [w]                 Intrinsic width (px, orientation applied) —
 *                                        recorded at upload so the grid can lay out
 *                                        rows before any image loads.
 * @property {number} [h]                 Intrinsic height (px)
 * @property {string} [caption]
 * @property {string} [alt]
 * @property {string} [takenAt]           ISO time the photo was taken (from EXIF).
 *                                        Orders the map path. Public.
 * @property {{lat: number, lon: number}} [coords]
 *                                        Location shown on the map. Only set when
 *                                        the author explicitly applies it. Public.
 * @property {string} [section]           Title of a section that STARTS at this photo.
 *                                        The dump shows a heading here and a "jump
 *                                        to" link at the top. Travels with the photo
 *                                        when the dump is reordered. Public.
 * @property {DumpPhotoAudio} [audio]     Spoken caption. In slideshow mode it plays
 *                                        before the countdown to the next photo.
 * @property {DumpPhotoExif} [exif]       Raw values read from the file at upload,
 *                                        kept so a location can be applied later.
 *                                        PRIVATE — stripped by publicStory() before
 *                                        a story reaches a visitor.
 *
 * @typedef {Object} DumpBlock            Photo dump — an unbounded pile of photos
 *                                        from a trip, laid out as justified rows
 *                                        with a lightbox. Bulk-uploaded.
 * @property {'dump'} type
 * @property {string} id
 * @property {DumpPhoto[]} photos
 * @property {string} [caption]           Caption for the whole dump
 * @property {'wide'|'full'} [size]
 * @property {'s'|'m'|'l'} [rowSize]      Target row height (default 'm')
 * @property {number} [slideshowSeconds]  Seconds per photo in slideshow mode (default 5)
 * @property {'none'|'pins'|'path'} [map] Map under the grid: off, a pin per geotagged
 *                                        photo, or pins joined chronologically (default 'none')
 *
 * @typedef {Object} PullBlock
 * @property {'pull'} type
 * @property {string} id
 * @property {string} body
 *
 * @typedef {Object} AsideBlock
 * @property {'aside'} type
 * @property {string} id
 * @property {string} body
 * @property {string} [label]
 *
 * @typedef {Object} AudioBlock
 * @property {'audio'} type
 * @property {string} id
 * @property {string} r2Key            R2 object key for the recording
 * @property {string} mimeType         e.g. 'audio/webm', 'audio/mp4'
 * @property {number} [durationMs]
 * @property {string} [caption]        Short note shown under the player
 * @property {string} [transcript]     Full transcript, shown under the player
 * @property {TranscriptWord[]} [words] Word timings, when the transcription gave
 *                                     them: the story page highlights each word
 *                                     as it is spoken
 * @property {string} [transcriptModel]
 * @property {string} [draftId]        For looking the file up against R2 —
 *                                     the playback endpoint needs to know
 *                                     which draft's media folder to look in.
 *
 * @typedef {Object} VideoBlock
 * @property {'video'} type
 * @property {string} id
 * @property {string} r2Key            R2 object key for the video file
 * @property {string} mimeType         e.g. 'video/mp4', 'video/webm'
 * @property {number} [durationMs]
 * @property {string} [caption]
 * @property {string} [posterImageId]  Cloudflare Images id for a still poster
 * @property {'inline'|'wide'|'full'} [size]
 * @property {string} [aspect]         e.g. '16/9', '9/16' — controls layout
 *
 * @typedef {Object} DividerBlock
 * @property {'divider'} type
 * @property {string} id
 * @property {string} [place]
 * @property {string} [date]
 *
 * @typedef {Object} HeadingBlock     A section title inside a story.
 * @property {'heading'} type
 * @property {string} id
 * @property {string} body             One line of plain text
 *
 * @typedef {Object} CodeBlock        Something to type or copy exactly: a command, a config snippet.
 * @property {'code'} type
 * @property {string} id
 * @property {string} body             Shown as written, in a fixed-width face; no links, no wrapping
 * @property {string} [caption]        Short note shown under it
 *
 * @typedef {TextBlock|PhotoBlock|DiptychBlock|GalleryBlock|DumpBlock|PullBlock|AsideBlock|DividerBlock|HeadingBlock|CodeBlock|AudioBlock|VideoBlock} Block
 *
 * @typedef {Object} WeatherSnapshot
 * @property {number} tempC
 * @property {number} tempF
 * @property {string} conditions
 * @property {number} [windKph]
 * @property {number} [humidity]
 * @property {'open-meteo'} source
 * @property {string} fetchedAt
 *
 * @typedef {Object} AstroSnapshot
 * @property {number} moonPhase           0..1, 0/1 = new, 0.5 = full
 * @property {string} moonPhaseLabel      "Waxing crescent" etc.
 * @property {number} moonIllumination    0..1
 * @property {string} sunrise             ISO
 * @property {string} sunset              ISO
 * @property {string} [astronomicalDawn]  ISO
 * @property {string} [astronomicalDusk]  ISO
 *
 * @typedef {Object} Story
 * @property {string} slug
 * @property {string} title
 * @property {string} date              ISO yyyy-mm-dd
 * @property {string} [location]
 * @property {{ lat: number, lon: number, accuracy?: number, approximate?: true }} [coords]
 *                                      GPS coords carried over from the draft. Stored exactly;
 *                                      publicStory() rounds or drops them for visitors
 *                                      (`approximate: true` marks a rounded pair).
 * @property {WeatherSnapshot} [weather] Weather captured at the moment GPS was tapped
 * @property {AstroSnapshot} [astro]     Astronomical state at capture time + place
 * @property {string} [summary]
 * @property {string} [coverImageId]    Cloudflare Images ID for the cover
 * @property {{x: number, y: number}} [coverFocus]   Focal point for cover crop (0..1, default 0.5/0.5)
 * @property {string[]} [tags]
 * @property {Block[]} blocks
 * @property {string} createdAt         ISO timestamp
 * @property {string} updatedAt         ISO timestamp
 * @property {string} [publishedAt]     ISO timestamp: when it first stopped being a draft. Set by the server, once.
 * @property {boolean} [draft]
 * @property {'public' | 'private'} [visibility]   Absent = public. Private stories are for invited members only (see lib/story-access.js)
 *
 * @typedef {Pick<Story, 'slug'|'title'|'date'|'location'|'summary'|'coverImageId'|'tags'|'draft'|'visibility'>} StorySummary
 */

export {};
