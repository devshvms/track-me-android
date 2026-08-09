package `in`.shvms.trackme.ui.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser exists because users were reading raw markdown — literal `###` and `####` — in the
 * update dialog. These assert the markers are consumed into structure rather than displayed.
 */
class ReleaseNotesParserTest {

    @Test
    fun `heading markers become levels, not text`() {
        val blocks = parseReleaseNotes("### TrackMe Release v1.6.6")
        assertEquals(listOf(NoteBlock.Heading(3, "TrackMe Release v1.6.6")), blocks)
    }

    @Test
    fun `the exact body users saw in 1_6_6 renders with no stray hashes`() {
        // Verbatim shape of the old git-log release body from the reported screenshot.
        val body = """
            ### TrackMe Release v1.6.6

            #### Changes & Highlights
            - Merge branch 'feat/1.6.6-edge-to-edge' (27aa403)
            - docs(distribution): release notes for 1.6.6 (1a2d79d)
        """.trimIndent()

        val blocks = parseReleaseNotes(body)

        assertEquals(NoteBlock.Heading(3, "TrackMe Release v1.6.6"), blocks.first())
        assertTrue(blocks.any { it == NoteBlock.Heading(4, "Changes & Highlights") })
        assertEquals(2, blocks.filterIsInstance<NoteBlock.Bullet>().size)
        // The point of the fix: no rendered text still carries markdown punctuation.
        val rendered = blocks.mapNotNull {
            when (it) {
                is NoteBlock.Heading -> it.text
                is NoteBlock.Bullet -> it.text
                is NoteBlock.Paragraph -> it.text
                NoteBlock.Space -> null
            }
        }
        assertTrue("markdown leaked into rendered text: $rendered", rendered.none { it.startsWith("#") })
        assertTrue(rendered.none { it.startsWith("- ") })
    }

    @Test
    fun `all three bullet markers are recognised`() {
        val blocks = parseReleaseNotes("- dash\n* star\n• dot")
        assertEquals(
            listOf(NoteBlock.Bullet("dash"), NoteBlock.Bullet("star"), NoteBlock.Bullet("dot")),
            blocks,
        )
    }

    @Test
    fun `the human-written whatsnew format parses as prose plus bullets`() {
        // Matches distribution/whatsnew/whatsnew-en-US, now the source for release bodies.
        val blocks = parseReleaseNotes("TrackMe 1.7.1\n\n• Fixed a crash on the map.\n")
        assertEquals(NoteBlock.Paragraph("TrackMe 1.7.1"), blocks[0])
        assertEquals(NoteBlock.Space, blocks[1])
        assertEquals(NoteBlock.Bullet("Fixed a crash on the map."), blocks[2])
    }

    @Test
    fun `indentation becomes nesting depth`() {
        val blocks = parseReleaseNotes("- top\n  - nested\n    - deeper")
        assertEquals(listOf(0, 1, 2), blocks.filterIsInstance<NoteBlock.Bullet>().map { it.depth })
    }

    @Test
    fun `blank runs collapse and never bookend the output`() {
        val blocks = parseReleaseNotes("\n\n\nfirst\n\n\n\nsecond\n\n\n")
        assertEquals(
            listOf(NoteBlock.Paragraph("first"), NoteBlock.Space, NoteBlock.Paragraph("second")),
            blocks,
        )
    }

    @Test
    fun `inline markers are stripped from the visible text`() {
        assertEquals("bold and code and italic", "**bold** and `code` and _italic_".toInlineAnnotated().text)
    }

    @Test
    fun `underscores inside words are left alone`() {
        // Otherwise identifiers like feature_flag_name would render as broken italics.
        assertEquals("some_identifier_here", "some_identifier_here".toInlineAnnotated().text)
    }
}
