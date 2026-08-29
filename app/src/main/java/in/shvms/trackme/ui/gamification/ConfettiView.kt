package `in`.shvms.trackme.ui.gamification

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.random.Random

data class ConfettiParticle(
    val x: Float,
    var y: Float,
    val speed: Float,
    val color: Color,
    val size: Float,
    val rotation: Float,
    val rotationSpeed: Float
)

@Composable
fun ConfettiView(
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.Magenta, Color.Cyan)
) {
    val particles = remember {
        List(100) {
            ConfettiParticle(
                x = Random.nextFloat(),
                y = Random.nextFloat() * -1f, // start above screen
                speed = Random.nextFloat() * 0.02f + 0.01f,
                color = colors.random(),
                size = Random.nextFloat() * 20f + 10f,
                rotation = Random.nextFloat() * 360f,
                rotationSpeed = Random.nextFloat() * 10f - 5f
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "confetti")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "confetti_progress"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        particles.forEach { particle ->
            // Manual integration over progress to allow wrapping
            val currentY = (particle.y + progress * particle.speed * 100f) % 1.5f
            if (currentY > -0.2f && currentY < 1.2f) {
                rotate(degrees = particle.rotation + progress * particle.rotationSpeed * 360f, pivot = Offset(particle.x * width, currentY * height)) {
                    drawRect(
                        color = particle.color,
                        topLeft = Offset(particle.x * width, currentY * height),
                        size = androidx.compose.ui.geometry.Size(particle.size, particle.size)
                    )
                }
            }
        }
    }
}
