package com.jirofeingold.pairfortwo.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import kotlin.math.roundToInt

/**
 * The 0–29 points slider — port of the iOS `PointsSlider`.
 *
 * Twenty-nine because that is the highest possible hand in cribbage, so the track spans everything
 * a player could ever need to claim at once. Dragging steps in whole points, with a haptic tick at
 * each one that strengthens as the number climbs.
 *
 * The gesture is a *relative* drag from wherever it starts, not an absolute position, so a player
 * can nudge a value up or down without their thumb jumping the knob to the touch point.
 */
@Composable
fun PointsSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    isDragging: Boolean,
    onDraggingChange: (Boolean) -> Unit,
    primary: Color,
    deep: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Fires on each step, with 0…1 progress along the track, for the escalating tick. */
    onTick: (Double) -> Unit = {},
) {
    val knobSize = 32.dp
    val trackHeight = 10.dp

    BoxWithConstraints(
        modifier
            // A hand-drawn track and a pointerInput are invisible to a screen reader, and this is
            // the control the whole game turns on. Declaring it a slider gives TalkBack both a
            // reading of the staged value and a way to change it without dragging.
            .semantics {
                contentDescription = "Points to add"
                stateDescription = if (value == 0) "No points staged" else "$value points"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.toFloat(),
                    range = 0f..MAX_VALUE.toFloat(),
                    steps = MAX_VALUE - 1,
                )
                if (enabled) {
                    setProgress { target ->
                        val next = target.roundToInt().coerceIn(0, MAX_VALUE)
                        onValueChange(next)
                        onCommit(next)
                        true
                    }
                }
            },
    ) {
        val usable = maxWidth - knobSize
        val progress = value.toFloat() / MAX_VALUE

        // Latest values, so the long-lived pointerInput doesn't capture stale ones.
        //
        // The step size is one of them, and deliberately so. It used to be `pointerInput(usable)`,
        // which tears the gesture down and rebuilds it whenever the track's width changes — and the
        // track *does* change width mid-drag, because the +N button beside it grows when its label
        // goes from "+9" to "+10". Dragging past 10 therefore cancelled the drag every time and the
        // knob stopped dead until you let go and grabbed it again. Reading the step per event
        // instead means the gesture survives a resize, whatever causes it.
        val stepPx = with(LocalDensity.current) { (usable.toPx() / MAX_VALUE).coerceAtLeast(1f) }
        val currentStep by rememberUpdatedState(stepPx)
        val currentValue by rememberUpdatedState(value)
        val currentChange by rememberUpdatedState(onValueChange)
        val currentCommit by rememberUpdatedState(onCommit)
        val currentTick by rememberUpdatedState(onTick)
        val currentDragging by rememberUpdatedState(onDraggingChange)
        var dragStartValue by remember { mutableIntStateOf(0) }
        var accumulated by remember { mutableIntStateOf(0) }

        val knobScale by animateFloatAsState(
            if (isDragging) 1.12f else 1f,
            spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
            label = "knob",
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .then(
                    if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                dragStartValue = currentValue
                                accumulated = 0
                                currentDragging(true)
                            },
                            onDragEnd = {
                                currentDragging(false)
                                if (currentValue > 0) currentCommit(currentValue)
                            },
                            onDragCancel = { currentDragging(false) },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                accumulated += dragAmount.x.roundToInt()
                                val delta = (accumulated / currentStep).roundToInt()
                                val next = (dragStartValue + delta).coerceIn(0, MAX_VALUE)
                                if (next != currentValue) {
                                    currentChange(next)
                                    currentTick(next.toDouble() / MAX_VALUE)
                                }
                            },
                        )
                    },
                ),
        ) {
            val trackH = trackHeight.toPx()
            val knobPx = knobSize.toPx()
            val centreY = size.height / 2
            val usablePx = size.width - knobPx

            // The empty track.
            drawRoundRect(
                color = Color.White.copy(alpha = 0.08f),
                topLeft = Offset(0f, centreY - trackH / 2),
                size = Size(size.width, trackH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2),
            )

            // A tick every five points, so a target value can be found without staring at a number.
            for (i in 0..MAX_VALUE) {
                if (i % 5 != 0) continue
                val x = knobPx / 2 + usablePx * (i.toFloat() / MAX_VALUE)
                drawLine(
                    color = Color.White.copy(alpha = 0.28f),
                    start = Offset(x, centreY - 3.dp.toPx()),
                    end = Offset(x, centreY + 3.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // The filled portion, in the player's colours.
            val fillWidth = (usablePx * progress + knobPx / 2).coerceAtLeast(trackH)
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(deep, primary), endX = fillWidth),
                topLeft = Offset(0f, centreY - trackH / 2),
                size = Size(fillWidth, trackH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2),
            )

            drawKnob(
                centre = Offset(knobPx / 2 + usablePx * progress, centreY),
                radius = knobPx / 2 * knobScale,
                primary = primary,
            )
        }
    }
}

private fun DrawScope.drawKnob(centre: Offset, radius: Float, primary: Color) {
    // A soft coloured halo, then the white knob with a subtle tint down its face.
    drawCircle(primary.copy(alpha = 0.35f), radius * 1.45f, centre)
    drawCircle(Color.White, radius, centre)
    drawCircle(
        brush = Brush.verticalGradient(
            listOf(primary.copy(alpha = 0f), primary.copy(alpha = 0.25f)),
            startY = centre.y - radius,
            endY = centre.y + radius,
        ),
        radius = radius,
        center = centre,
    )
}

/** The highest hand in cribbage, and so the most a single claim ever needs to be. */
const val MAX_VALUE = 29
