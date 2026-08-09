package `in`.shvms.trackme.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Vector art for the walkthrough. Drawn rather than shipped as bitmaps so it themes with the app
 * and adds nothing to the APK.
 *
 * Everything here reads its colours from the theme, so the same drawings work on the light surface
 * as on navy — the brand is dark-first, but the app is not dark-only.
 */

/** A route drawn through normalised (0..1) control points, scaled to whatever box it lands in. */
private fun DrawScope.routePath(
    points: List<Pair<Float, Float>>,
    inset: Float = 0f,
): Path = Path().apply {
    val w = size.width - inset * 2
    val h = size.height - inset * 2
    fun px(p: Pair<Float, Float>) = Offset(inset + p.first * w, inset + p.second * h)
    val first = px(points.first())
    moveTo(first.x, first.y)
    for (i in 1 until points.size) {
        val prev = px(points[i - 1])
        val cur = px(points[i])
        val midX = (prev.x + cur.x) / 2f
        cubicTo(midX, prev.y, midX, cur.y, cur.x, cur.y)
    }
}

// ---------------------------------------------------------------------------------------------
// 0 · Welcome
// ---------------------------------------------------------------------------------------------

@Composable
fun WelcomeMark(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val ground = MaterialTheme.colorScheme.surface
    val trail = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val cx = size.width / 2f
        val pinTop = size.height * 0.12f
        val r = size.minDimension * 0.17f

        // Teardrop: circle plus a triangle to the point.
        drawCircle(accent, radius = r, center = Offset(cx, pinTop + r))
        drawPath(
            Path().apply {
                moveTo(cx - r * 0.72f, pinTop + r * 1.42f)
                lineTo(cx + r * 0.72f, pinTop + r * 1.42f)
                lineTo(cx, pinTop + r * 2.75f)
                close()
            },
            accent,
        )
        drawCircle(ground, radius = r * 0.38f, center = Offset(cx, pinTop + r))

        drawPath(
            routePath(
                listOf(0.06f to 0.92f, 0.3f to 0.82f, 0.5f to 0.9f, 0.72f to 0.79f, 0.94f to 0.86f),
            ),
            trail.copy(alpha = 0.5f),
            style = Stroke(width = size.minDimension * 0.035f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 1 · The press-and-hold gesture
// ---------------------------------------------------------------------------------------------

/**
 * The radial persona picker mid-gesture: ring fanned out, one option under the thumb.
 *
 * A still frame of the interaction rather than an icon of it — the gesture is the one thing a
 * fresh install cannot guess, and this screen replaces the hint pill that used to say so in words.
 */
@Composable
fun RideGestureArt(selectedLabel: String, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val node = MaterialTheme.colorScheme.surfaceVariant
    val nodeEdge = MaterialTheme.colorScheme.outlineVariant
    val ghost = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val ring = size.minDimension * 0.36f
            val nodeR = size.minDimension * 0.105f

            drawCircle(
                ghost.copy(alpha = 0.28f),
                radius = ring,
                center = c,
                style = Stroke(
                    width = 1.4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f)),
                ),
            )

            // Five satellites; the lower-right one is the selection under the thumb.
            val angles = listOf(-90f, -34f, 22f, 202f, 246f)
            angles.forEachIndexed { i, deg ->
                val rad = Math.toRadians(deg.toDouble())
                val p = Offset(c.x + (ring * kotlin.math.cos(rad)).toFloat(), c.y + (ring * kotlin.math.sin(rad)).toFloat())
                val chosen = i == 2
                drawCircle(if (chosen) accent.copy(alpha = 0.18f) else node, radius = nodeR, center = p)
                drawCircle(
                    if (chosen) accent else nodeEdge,
                    radius = nodeR,
                    center = p,
                    style = Stroke(width = if (chosen) 1.8.dp.toPx() else 1.dp.toPx()),
                )
            }

            // Centre control, then the thumb resting on the chosen option.
            drawCircle(accent, radius = size.minDimension * 0.16f, center = c)
            drawPath(
                Path().apply {
                    val s = size.minDimension * 0.062f
                    moveTo(c.x - s * 0.55f, c.y - s)
                    lineTo(c.x + s * 0.95f, c.y)
                    lineTo(c.x - s * 0.55f, c.y + s)
                    close()
                },
                onAccent,
            )

            val thumbRad = Math.toRadians(22.0)
            val thumb = Offset(
                c.x + (ring * kotlin.math.cos(thumbRad)).toFloat(),
                c.y + (ring * kotlin.math.sin(thumbRad)).toFloat(),
            )
            drawCircle(
                ghost.copy(alpha = 0.75f),
                radius = nodeR * 1.5f,
                center = thumb,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        // The selection reads as a word, not a glyph, so the screen names what the drag chooses.
        Box(
            Modifier
                .offset(x = 46.dp, y = 20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(
                selectedLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 2 · History  — improved
// ---------------------------------------------------------------------------------------------

/**
 * What a saved ride actually looks like: a route thumbnail with its real numbers, stacked to read
 * as a list.
 *
 * The first pass used three grey placeholder bars, which described "a list" without showing what
 * is in one. A route shape and a distance are the two things a person recognises their own ride
 * by, so those are what the screen shows.
 */
@Composable
fun HistoryArt(
    primaryStat: String,
    secondaryStat: String,
    personaLabel: String,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val card = MaterialTheme.colorScheme.surfaceVariant
    val edge = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(0.86f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Two dimmed cards behind, so the front one reads as the top of a list.
            repeat(2) { i ->
                Box(
                    Modifier
                        .fillMaxWidth(if (i == 0) 0.80f else 0.90f)
                        .align(Alignment.CenterHorizontally)
                        .height(11.dp)
                        .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp))
                        .background(card.copy(alpha = if (i == 0) 0.35f else 0.6f)),
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(card)
                    .border(1.dp, edge, RoundedCornerShape(11.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                // Route thumbnail.
                Canvas(
                    Modifier
                        .size(width = 54.dp, height = 40.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    val pts = listOf(
                        0.10f to 0.80f, 0.30f to 0.34f, 0.48f to 0.58f,
                        0.66f to 0.22f, 0.90f to 0.44f,
                    )
                    drawPath(
                        routePath(pts, inset = 3.dp.toPx()),
                        accent,
                        style = Stroke(width = 2.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                    // Start and finish, so the thumbnail reads as a journey with a direction.
                    val w = size.width - 6.dp.toPx()
                    val h = size.height - 6.dp.toPx()
                    val i = 3.dp.toPx()
                    drawCircle(accent, 2.6.dp.toPx(), Offset(i + 0.10f * w, i + 0.80f * h))
                    drawCircle(accent, 2.6.dp.toPx(), Offset(i + 0.90f * w, i + 0.44f * h))
                }

                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        primaryStat,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "$secondaryStat · $personaLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 3 · Ride together
// ---------------------------------------------------------------------------------------------

@Composable
fun TogetherArt(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ground = MaterialTheme.colorScheme.surface
    val second = MaterialTheme.colorScheme.tertiary

    Canvas(modifier) {
        val pts = listOf(0.08f to 0.84f, 0.32f to 0.62f, 0.55f to 0.46f, 0.78f to 0.30f, 0.94f to 0.20f)
        drawPath(
            routePath(pts),
            accent.copy(alpha = 0.45f),
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 8f)),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
        )

        // Three riders on the same route — the whole point of the screen is that they share one map.
        val riders = listOf(
            Triple(0.22f, 0.72f, accent),
            Triple(0.55f, 0.46f, second),
            Triple(0.86f, 0.25f, muted),
        )
        riders.forEach { (fx, fy, colour) ->
            val p = Offset(fx * size.width, fy * size.height)
            val r = size.minDimension * 0.085f
            drawCircle(ground, radius = r * 1.28f, center = p)
            drawCircle(colour, radius = r, center = p)
            drawCircle(ground, radius = r * 0.36f, center = Offset(p.x, p.y - r * 0.16f))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 4 · Permissions  — new
// ---------------------------------------------------------------------------------------------

/**
 * A day as a track, with location on only for the stretch that is a ride.
 *
 * The permissions screen makes a specific promise — location is used during an active ride or
 * group and not otherwise — and that promise is the reason someone grants it. The first pass
 * asserted it in prose and drew nothing. This draws the claim, so the shape of it is visible
 * before the system dialog appears.
 */
@Composable
fun LocationScopeArt(
    offLabel: String,
    onLabel: String,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(46.dp),
        ) {
            val trackY = size.height * 0.74f
            val trackH = 7.dp.toPx()
            val activeStart = size.width * 0.36f
            val activeEnd = size.width * 0.68f

            drawRoundRect(
                color = dim.copy(alpha = 0.55f),
                topLeft = Offset(0f, trackY - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2f),
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(activeStart, trackY - trackH / 2f),
                size = Size(activeEnd - activeStart, trackH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2f),
            )

            // A pin above the live stretch, tying "location on" to the ride and nothing else.
            val pinX = (activeStart + activeEnd) / 2f
            val pinR = 7.dp.toPx()
            val pinY = trackY - trackH - pinR * 2.2f
            drawCircle(accent, radius = pinR, center = Offset(pinX, pinY))
            drawPath(
                Path().apply {
                    moveTo(pinX - pinR * 0.66f, pinY + pinR * 0.68f)
                    lineTo(pinX + pinR * 0.66f, pinY + pinR * 0.68f)
                    lineTo(pinX, pinY + pinR * 2.05f)
                    close()
                },
                accent,
            )
            // Tick marks bounding the active stretch.
            listOf(activeStart, activeEnd).forEach { x ->
                drawLine(
                    accent.copy(alpha = 0.5f),
                    start = Offset(x, trackY + trackH),
                    end = Offset(x, trackY + trackH + 5.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(offLabel, style = MaterialTheme.typography.labelSmall, color = muted)
            Text(
                onLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            Text(offLabel, style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}
