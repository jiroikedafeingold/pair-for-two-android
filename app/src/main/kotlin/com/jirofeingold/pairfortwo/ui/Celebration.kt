package com.jirofeingold.pairfortwo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Seconds since this entered composition, updated every frame.
 *
 * The celebration effects are all time-driven particle systems rather than per-particle animations:
 * a hundred and twenty `Animatable`s would be a hundred and twenty animations to schedule, where one
 * clock and some arithmetic in a `Canvas` is a single draw. This is the Compose equivalent of
 * SwiftUI's `TimelineView(.animation)`, which is what the Swift uses.
 */
@Composable
fun rememberElapsedSeconds(): State<Float> {
    val elapsed = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> elapsed.floatValue = (now - start) / 1_000_000_000f }
        }
    }
    return elapsed
}

// ---- Fireworks ----

private data class Burst(
    val cx: Float,
    val cy: Float,
    val t0: Float,
    val color: Color,
    val count: Int,
    val phase: Float,
)

/**
 * Continuous bursts of particles that shoot outward from random points, arc down under gravity and
 * fade — layered behind the win card. Port of the iOS `FireworksView`; no assets, just a `Canvas`.
 */
@Composable
fun FireworksView(colors: List<Color>, modifier: Modifier = Modifier) {
    val cycle = 6.5f
    val life = 1.6f
    val bursts = remember(colors) {
        List(34) {
            Burst(
                cx = Random.nextFloat() * 0.84f + 0.08f,
                cy = Random.nextFloat() * 0.60f + 0.08f,
                t0 = Random.nextFloat() * (cycle - life),
                color = colors.random(),
                count = Random.nextInt(22, 37),
                phase = Random.nextFloat() * (2 * Math.PI.toFloat()),
            )
        }
    }
    val elapsed by rememberElapsedSeconds()

    Canvas(modifier) {
        val now = elapsed % cycle
        for (b in bursts) {
            val age = now - b.t0
            if (age < 0f || age >= life) continue
            val progress = age / life
            val alpha = 1f - progress
            val centre = Offset(b.cx * size.width, b.cy * size.height)
            for (k in 0 until b.count) {
                val angle = k.toFloat() / b.count * 2 * Math.PI.toFloat() + b.phase
                val speed = 155f + (k % 3) * 34f
                val dist = speed * age
                val gravity = 175f * age * age
                val r = 4.5f * alpha + 1f
                drawCircle(
                    color = b.color.copy(alpha = alpha),
                    radius = r,
                    center = Offset(
                        centre.x + cos(angle) * dist,
                        centre.y + sin(angle) * dist + gravity,
                    ),
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }
}

// ---- Confetti ----

private data class ConfettiPiece(
    val color: Color,
    val startX: Float,
    val endX: Float,
    val startRotation: Float,
    val endRotation: Float,
    val size: Float,
    val duration: Float,
    val delay: Float,
    val shape: Int,
)

/**
 * A one-shot fall of confetti — port of the iOS `ConfettiBurst`.
 *
 * Each piece drifts sideways, spins, and fades as it goes, on its own duration and delay so the
 * fall never looks like a single sheet of paper.
 */
@Composable
fun ConfettiBurst(colors: List<Color>, modifier: Modifier = Modifier) {
    val pieces = remember(colors) {
        List(120) {
            ConfettiPiece(
                color = colors.random(),
                startX = Random.nextFloat() * 0.6f + 0.2f,
                endX = Random.nextFloat(),
                startRotation = Random.nextFloat() * 360f,
                endRotation = 360f + Random.nextFloat() * 360f,
                size = Random.nextFloat() * 8f + 6f,
                duration = Random.nextFloat() * 2f + 2.5f,
                delay = Random.nextFloat() * 0.6f,
                shape = Random.nextInt(3),
            )
        }
    }
    val elapsed by rememberElapsedSeconds()

    Canvas(modifier) {
        for (p in pieces) {
            val t = ((elapsed - p.delay) / p.duration).coerceIn(0f, 1f)
            if (t <= 0f) continue
            // Ease-in, matching the Swift's .easeIn — the fall accelerates.
            val e = t * t
            val x = (p.startX + (p.endX - p.startX) * e) * size.width
            val y = -40f + (size.height + 80f) * e
            val rotation = p.startRotation + (p.endRotation - p.startRotation) * e
            val alpha = 1f - t
            if (alpha <= 0f) continue

            val w = p.size.dp.toPx()
            val h = if (p.shape == 0) w * 0.55f else w
            rotate(rotation, Offset(x, y)) {
                when (p.shape) {
                    0 -> drawRect(
                        p.color.copy(alpha = alpha),
                        topLeft = Offset(x - w / 2, y - h / 2),
                        size = Size(w, h),
                    )
                    1 -> drawCircle(p.color.copy(alpha = alpha), w / 2, Offset(x, y))
                    else -> drawRoundRect(
                        p.color.copy(alpha = alpha),
                        topLeft = Offset(x - w / 2, y - h / 2),
                        size = Size(w, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
                    )
                }
            }
        }
    }
}

// ---- Rain, for the loser ----

private data class RainDrop(val x: Float, val t0: Float, val speed: Float, val length: Float)

/** Slow, sparse rain behind the loser card — the counterpart to the winner's confetti. */
@Composable
fun SadRainView(modifier: Modifier = Modifier) {
    val drops = remember {
        List(70) {
            RainDrop(
                x = Random.nextFloat(),
                t0 = Random.nextFloat() * 2.4f,
                speed = Random.nextFloat() * 0.35f + 0.45f,
                length = Random.nextFloat() * 18f + 10f,
            )
        }
    }
    val elapsed by rememberElapsedSeconds()

    Canvas(modifier) {
        for (d in drops) {
            val cycle = 1f / d.speed * 2.4f
            val t = ((elapsed + d.t0) % cycle) / cycle
            val y = t * (size.height + 60f) - 30f
            val len = d.length.dp.toPx()
            drawLine(
                color = Color.White.copy(alpha = 0.18f),
                start = Offset(d.x * size.width, y),
                end = Offset(d.x * size.width - 3f, y + len),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}
