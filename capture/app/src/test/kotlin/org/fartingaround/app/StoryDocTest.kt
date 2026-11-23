package org.fartingaround.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.fartingaround.app.consume.Story
import org.fartingaround.app.data.normalizeSiteAddress
import org.fartingaround.app.model.StoryDoc
import org.fartingaround.app.model.appendBlock
import org.fartingaround.app.model.blocks
import org.fartingaround.app.model.forServer
import org.fartingaround.app.model.moveBlock
import org.fartingaround.app.model.notes
import org.fartingaround.app.model.removeBlock
import org.fartingaround.app.model.str
import org.fartingaround.app.model.updateBlock
import org.fartingaround.app.model.with
import org.fartingaround.app.model.withNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The story document as the phone edits it, and the feed as the reader decodes
 * it. Plain JVM tests: nothing here needs a phone.
 *
 * These are also the written rules for any other app (the Swift one) that
 * edits the same documents: what must survive an edit, and what a reader does
 * with something it does not recognise.
 */
class StoryDocTest {
	private fun doc(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
	private fun JsonObject.ids() = blocks.map { it.str("id") }

	private val three = doc(
		"""{"title":"T","blocks":[{"type":"text","id":"a","body":"one"},{"type":"pull","id":"b","body":"two"},{"type":"divider","id":"c"}]}"""
	)

	@Test fun blocksAreAppendedRemovedAndEditedById() {
		val added = three.appendBlock(doc("""{"type":"text","id":"d","body":"four"}"""))
		assertEquals(listOf("a", "b", "c", "d"), added.ids())
		assertEquals(listOf("a", "c"), three.removeBlock("b").ids())
		val edited = three.updateBlock("b") { it.with("body", "TWO") }
		assertEquals("TWO", edited.blocks[1].str("body"))
		assertEquals("one", edited.blocks[0].str("body"))
		// The original is never changed in place.
		assertEquals("two", three.blocks[1].str("body"))
	}

	@Test fun movingABlockStopsAtTheEnds() {
		assertEquals(listOf("b", "a", "c"), three.moveBlock("b", -1).ids())
		assertEquals(listOf("a", "c", "b"), three.moveBlock("b", 1).ids())
		assertEquals(listOf("a", "b", "c"), three.moveBlock("a", -1).ids())
		assertEquals(listOf("a", "b", "c"), three.moveBlock("c", 1).ids())
		assertEquals(listOf("a", "b", "c"), three.moveBlock("nope", 1).ids())
	}

	@Test fun notesAreTheFirstTextBlock() {
		assertEquals("one", three.notes)
		assertEquals("changed", three.withNotes("changed").notes)
		// Blanking the notes removes that block and leaves the rest.
		assertEquals(listOf("b", "c"), three.withNotes("  ").ids())
		// A story with no text block gets one at the top.
		val photoOnly = doc("""{"blocks":[{"type":"photo","id":"p","imageId":"i"}]}""")
		val withText = photoOnly.withNotes("hello")
		assertEquals("text", withText.blocks.first().str("type"))
		assertEquals("hello", withText.notes)
		assertEquals("p", withText.blocks.last().str("id"))
		// Blank notes on a story that has none change nothing.
		assertEquals(photoOnly, photoOnly.withNotes(""))
	}

	@Test fun whatThePhoneDoesNotKnowSurvivesAnEdit() {
		val d = doc(
			"""{"title":"T","fromTheFuture":{"x":1},"blocks":[{"type":"hologram","id":"h","depth":3},{"type":"code","id":"c","body":"fa setup","caption":"On the server"},{"type":"text","id":"t","body":"hi","mood":"calm"}]}"""
		)
		val edited = d.withNotes("hello").moveBlock("t", -1).updateBlock("c") { it.with("body", "fa doctor") }
		assertEquals(JsonPrimitive(1), edited["fromTheFuture"]!!.jsonObject["x"])
		val hologram = edited.blocks.first { it.str("id") == "h" }
		assertEquals(JsonPrimitive(3), hologram["depth"])
		assertEquals("calm", edited.blocks.first { it.str("id") == "t" }.str("mood"))
		val code = edited.blocks.first { it.str("id") == "c" }
		assertEquals("fa doctor", code.str("body"))
		assertEquals("On the server", code.str("caption"))
	}

	@Test fun theServerOwnsItsTimestamps() {
		val sent = doc("""{"title":"T","createdAt":"x","updatedAt":"y","blocks":[]}""").forServer()
		assertFalse("createdAt" in sent)
		assertFalse("updatedAt" in sent)
		assertEquals("T", sent.str("title"))
	}

	@Test fun aNewStoryStartsAsAnEmptyDraft() {
		val d = StoryDoc.empty()
		assertTrue(d.blocks.isEmpty())
		assertEquals(JsonPrimitive(true), d["draft"])
		assertEquals("", d.str("title"))
	}

	@Test fun theReaderDecodesHeadingsAndCode() {
		val s = Story.parse(
			"""{"slug":"s","date":"2026-11-23","blocks":[{"type":"heading","id":"h","body":"Get fa"},{"type":"code","id":"c","body":"fa setup\n  --yes","caption":""},{"type":"text","id":"t","body":"x"}]}""",
			"https://example.org/"
		)
		assertNotNull(s)
		val blocks = s!!.blocks
		assertEquals(3, blocks.size)
		assertEquals("Get fa", (blocks[0] as Story.Block.Heading).body)
		val code = blocks[1] as Story.Block.Code
		assertEquals("fa setup\n  --yes", code.body)
		assertNull("a blank caption is no caption", code.caption)
		assertEquals("https://example.org", s.base)
	}

	@Test fun theReaderSkipsWhatItDoesNotKnow() {
		val s = Story.parse(
			"""{"slug":"s","blocks":[{"type":"hologram","id":"h"},{"type":"text","body":"no id"},{"type":"text","id":"t","body":"kept"},"not a block"]}""",
			"https://example.org"
		)
		assertEquals(listOf("t"), s!!.blocks.map { it.id })
		// No slug: not a story this reader can address.
		assertNull(Story.parse("""{"blocks":[]}""", "https://example.org"))
		assertNull(Story.parse("not json", "https://example.org"))
	}

	@Test fun aTypedSiteAddressIsTidied() {
		assertEquals("https://example.org", normalizeSiteAddress(" example.org "))
		assertEquals("https://example.org", normalizeSiteAddress("https://Example.org/admin?x=1"))
		assertEquals("http://localhost:5173", normalizeSiteAddress("http://localhost:5173/"))
		assertEquals("https://example.org:8443", normalizeSiteAddress("example.org:8443"))
		assertNull(normalizeSiteAddress(""))
		assertNull(normalizeSiteAddress("not a site"))
	}
}
