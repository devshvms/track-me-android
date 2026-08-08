package `in`.shvms.trackme.ui.home.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

/**
 * Circular avatar markers for group members — SCOPE_1.7.0 §3.3.
 *
 * §3.3 calls this *"the single highest-craft element in the release, and the one most likely to be
 * done badly"*, and names the trap explicitly:
 *
 * > *"Performance — this is the trap. Google Maps markers need a `Bitmap`, and regenerating eight
 * > `BitmapDescriptor`s every 5–10 seconds will jank the map. The avatar bitmap must be built once
 * > per member per session, cached by member id, and only the `MarkerState.position` animated on
 * > subsequent syncs."*
 *
 * So the cache is keyed on everything that changes the *pixels* — member, initials, tint, and
 * freshness — and nothing that changes every sync. A position update never touches this class.
 *
 * The drawing approach deliberately mirrors `RideDetailScreen`'s canvas-drawn circle bitmaps
 * rather than inventing a second one (§3.3: *"Reference code exists… do not invent a second one."*)
 */
class MemberAvatarCache(private val context: Context, private val density: Float) {

    private data class Key(
        val uid: String,
        val initials: String?,
        val freshness: MarkerFreshness,
    )

    private val cache = mutableMapOf<Key, BitmapDescriptor>()

    /**
     * A descriptor for one member. Built on first request and reused thereafter — the whole point.
     *
     * §3.3's content priority is photo → initials → neutral glyph. The photo path needs an async
     * Coil fetch and is **not** implemented here: it would be a network fetch on the map's critical
     * path, and initials render immediately so a marker never pops in late. The hook is where it
     * belongs (this cache), so adding photos later is one method, not a redesign.
     */
    fun descriptorFor(
        uid: String,
        initials: String?,
        freshness: MarkerFreshness,
    ): BitmapDescriptor = cache.getOrPut(Key(uid, initials, freshness)) {
        BitmapDescriptorFactory.fromBitmap(draw(uid, initials, freshness))
    }

    /**
     * Dropped when the group ends. Bitmaps are small but a session's worth of them is still real
     * memory, and a member id from a previous group is never valid in the next one.
     */
    fun clear() {
        cache.clear()
    }

    private fun draw(uid: String, initials: String?, freshness: MarkerFreshness): Bitmap {
        val size = (MARKER_DP * density).toInt().coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = size / 2f
        val ringWidth = RING_DP * density
        val radius = centre - ringWidth

        val stale = freshness == MarkerFreshness.STALE
        val fill = deterministicMarkerTint(uid)
        val ring = if (stale) SLATE_MUTED else CYAN_BRIGHT

        // Soft drop shadow so a marker stays legible over any map style (§3.3).
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 0, 0, 0)
            setShadowLayer(3f * density, 0f, 1f * density, Color.argb(90, 0, 0, 0))
        }
        canvas.drawCircle(centre, centre + 1f, radius, shadow)

        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fill
            // §3.3: "desaturate to ~40%" — a colour matrix rather than a different palette, so a
            // stale marker is visibly the same person, just faded.
            if (stale) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.4f) })
        }
        canvas.drawCircle(centre, centre, radius, body)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ringWidth
            color = ring
        }
        canvas.drawCircle(centre, centre, radius, ringPaint)

        val label = initials?.takeIf { it.isNotBlank() } ?: GLYPH
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = radius * (if (label == GLYPH) 1.1f else 0.85f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()
        text.getTextBounds(label, 0, label.length, bounds)
        canvas.drawText(label, centre, centre + bounds.height() / 2f, text)

        return bitmap
    }

    companion object {
        private const val MARKER_DP = 34f
        private const val RING_DP = 2f
        private const val GLYPH = "•"

        /** `cyan/bright` and the muted slate, per BRAND_SYSTEM. One accent, never a second hue. */
        private val CYAN_BRIGHT = Color.parseColor("#29B6F6")
        private val SLATE_MUTED = Color.parseColor("#94A3B8")
    }
}

@Composable
fun rememberMemberAvatarCache(): MemberAvatarCache {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    return remember(context, density) { MemberAvatarCache(context, density) }
}

/**
 * A stable per-member fill, drawn from a navy/cyan-safe ramp.
 *
 * §3.3: *"Initials render on a per-member deterministic tint… so two members are visually separable
 * at a glance"*, and §3.6: *"Member identity must never be conveyed by colour alone"* — hence the
 * initials on top and the name on tap. The hash is on the uid, so a member keeps the same colour
 * for the whole session and across a reconnect.
 */
fun deterministicMarkerTint(uid: String): Int = MARKER_RAMP[
    (uid.hashCode().toLong() and 0xFFFFFFFFL).rem(MARKER_RAMP.size.toLong()).toInt()
]

private val MARKER_RAMP = intArrayOf(
    Color.parseColor("#0277B6"), // cyan/deep
    Color.parseColor("#1E4976"),
    Color.parseColor("#2E5E8A"),
    Color.parseColor("#3E7CA8"),
    Color.parseColor("#155E75"),
    Color.parseColor("#334E68"),
)
