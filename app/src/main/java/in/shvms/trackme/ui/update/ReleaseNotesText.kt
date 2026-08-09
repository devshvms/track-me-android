package `in`.shvms.trackme.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Renders release notes as formatted text rather than a raw markdown blob.
 *
 * Users were seeing literal `###` and `####` because the notes were dropped into a plain `Text`.
 * This handles the subset that actually appears in a release body — headings, bullets, and inline
 * emphasis — and treats anything else as a paragraph, so unknown syntax degrades to readable prose
 * instead of leaking punctuation.
 */
@Composable
fun ReleaseNotes(
    notes: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(notes) { parseReleaseNotes(notes) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        blocks.forEach { block ->
            when (block) {
                is NoteBlock.Heading -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = block.text.toInlineAnnotated(),
                        style = when (block.level) {
                            1, 2 -> MaterialTheme.typography.titleMedium
                            3 -> MaterialTheme.typography.titleSmall
                            else -> MaterialTheme.typography.labelLarge
                        },
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                is NoteBlock.Bullet -> Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(start = (block.depth * 12).dp),
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = block.text.toInlineAnnotated(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is NoteBlock.Paragraph -> Text(
                    text = block.text.toInlineAnnotated(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                NoteBlock.Space -> Spacer(Modifier.height(6.dp))
            }
        }
    }
}

internal sealed interface NoteBlock {
    data class Heading(val level: Int, val text: String) : NoteBlock
    data class Bullet(val text: String, val depth: Int = 0) : NoteBlock
    data class Paragraph(val text: String) : NoteBlock
    data object Space : NoteBlock
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^(\s*)[-*•]\s+(.*)$""")

/**
 * Splits a markdown body into renderable blocks.
 *
 * Kept pure and internal so the parsing rules can be unit-tested without composing anything.
 */
internal fun parseReleaseNotes(raw: String): List<NoteBlock> {
    val blocks = mutableListOf<NoteBlock>()
    for (line in raw.trim().lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            // Collapse runs of blank lines; never lead with one.
            if (blocks.isNotEmpty() && blocks.last() != NoteBlock.Space) blocks += NoteBlock.Space
            continue
        }
        val heading = HEADING.matchEntire(trimmed)
        val bullet = BULLET.matchEntire(line)
        blocks += when {
            heading != null ->
                NoteBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            // Two leading spaces per nesting level, the common markdown convention.
            bullet != null ->
                NoteBlock.Bullet(bullet.groupValues[2].trim(), bullet.groupValues[1].length / 2)
            else -> NoteBlock.Paragraph(trimmed)
        }
    }
    return blocks.dropLastWhile { it == NoteBlock.Space }
}

private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+?)`|(?<![A-Za-z0-9_])_(.+?)_(?![A-Za-z0-9_])""")

/** Applies `**bold**`, `` `code` `` and `_italic_`, leaving the markers themselves out of the output. */
internal fun String.toInlineAnnotated(): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in INLINE.findAll(this@toInlineAnnotated)) {
        append(this@toInlineAnnotated.substring(cursor, match.range.first))
        val (bold, code, italic) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
        }
        cursor = match.range.last + 1
    }
    append(this@toInlineAnnotated.substring(cursor))
}
