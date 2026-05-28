package com.softrmk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.softrmk.R

private const val ROTARY_STEP_THRESHOLD = 42f
private const val CHEVRON_TOP_BOTTOM_OFFSET_DP = 16
private const val CHEVRON_SIDE_OFFSET_DP = 18
private const val TOP_BOTTOM_TOUCH_OFFSET_DP = 22
private const val SIDE_TOUCH_OFFSET_DP = 20

@Composable
fun CenterPad(
    modifier: Modifier,
    onTapCenter: () -> Unit,
    onStartHold: (String) -> Unit,
    onStopHold: () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = UiPalette.PanelBackground),
        shape = RoundedCornerShape(UiShapes.PanelCorner)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                ) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val dPadDiameter = minOf(maxWidth, maxHeight) * 0.84f
                        val okSize = dPadDiameter * 0.36f
                        val topBottomTouchWidth = dPadDiameter * 0.48f
                        val topBottomTouchHeight = dPadDiameter * 0.22f
                        val sideTouchWidth = dPadDiameter * 0.22f
                        val sideTouchHeight = dPadDiameter * 0.48f

                        Box(
                            modifier = Modifier
                                .size(dPadDiameter)
                                .align(Alignment.Center)
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val minDim = size.minDimension
                                val outerRadius = minDim * 0.48f
                                val innerRadius = minDim * 0.29f
                                val bevelRadius = minDim * 0.50f
                                val segmentSweep = 76f
                                val segmentStarts = listOf(232f, 322f, 52f, 142f)

                                drawCircle(color = Color(0xFF070707), radius = minDim * 0.50f)
                                drawCircle(
                                    color = Color(0xFF141416),
                                    radius = minDim * 0.485f,
                                    style = Stroke(width = minDim * 0.012f)
                                )
                                segmentStarts.forEach { startAngle ->
                                    val path = buildRingSegmentPath(center, outerRadius, innerRadius, startAngle, segmentSweep)
                                    drawPath(path = path, brush = UiBrushes.DpadSegment)
                                    drawPath(path = path, color = Color(0xFF4B4D50), style = Stroke(width = minDim * 0.006f))
                                    drawArc(
                                        color = Color(0x22FFFFFF),
                                        startAngle = startAngle + 6f,
                                        sweepAngle = segmentSweep - 12f,
                                        useCenter = false,
                                        topLeft = Offset(center.x - bevelRadius, center.y - bevelRadius),
                                        size = Size(bevelRadius * 2f, bevelRadius * 2f),
                                        style = Stroke(width = minDim * 0.006f, cap = StrokeCap.Round)
                                    )
                                }

                                drawCircle(Color(0xFF0A0A0B), radius = innerRadius + minDim * 0.015f)
                                drawCircle(
                                    color = UiPalette.RotaryAccent,
                                    radius = innerRadius + minDim * 0.004f,
                                    style = Stroke(width = minDim * 0.016f)
                                )
                                drawCircle(Color(0xFF09090A), radius = innerRadius - minDim * 0.01f)
                            }

                            ChevronIcon(
                                modifier = Modifier.align(Alignment.TopCenter).offset(y = CHEVRON_TOP_BOTTOM_OFFSET_DP.dp),
                                imageVector = Icons.Filled.KeyboardArrowUp
                            )
                            ChevronIcon(
                                modifier = Modifier.align(Alignment.CenterStart).offset(x = CHEVRON_SIDE_OFFSET_DP.dp),
                                imageVector = Icons.Filled.KeyboardArrowLeft
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(okSize)
                                    .background(brush = UiBrushes.OkButton, shape = CircleShape)
                                    .border(2.dp, Color(0xFF5A5A5A), CircleShape)
                                    .clickable { onTapCenter() }
                            ) {
                                Text(
                                    text = stringResource(R.string.center_ok),
                                    modifier = Modifier.align(Alignment.Center),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp
                                )
                            }
                            ChevronIcon(
                                modifier = Modifier.align(Alignment.CenterEnd).offset(x = (-CHEVRON_SIDE_OFFSET_DP).dp),
                                imageVector = Icons.Filled.KeyboardArrowRight
                            )
                            ChevronIcon(
                                modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-CHEVRON_TOP_BOTTOM_OFFSET_DP).dp),
                                imageVector = Icons.Filled.KeyboardArrowDown
                            )

                            HoldTouchZone(
                                keyName = "up",
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .offset(y = TOP_BOTTOM_TOUCH_OFFSET_DP.dp)
                                    .size(topBottomTouchWidth, topBottomTouchHeight),
                                onStartHold = onStartHold,
                                onStopHold = onStopHold
                            )
                            HoldTouchZone(
                                keyName = "left",
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = SIDE_TOUCH_OFFSET_DP.dp)
                                    .size(sideTouchWidth, sideTouchHeight),
                                onStartHold = onStartHold,
                                onStopHold = onStopHold
                            )
                            HoldTouchZone(
                                keyName = "right",
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .offset(x = (-SIDE_TOUCH_OFFSET_DP).dp)
                                    .size(sideTouchWidth, sideTouchHeight),
                                onStartHold = onStartHold,
                                onStopHold = onStopHold
                            )
                            HoldTouchZone(
                                keyName = "down",
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .offset(y = (-TOP_BOTTOM_TOUCH_OFFSET_DP).dp)
                                    .size(topBottomTouchWidth, topBottomTouchHeight),
                                onStartHold = onStartHold,
                                onStopHold = onStopHold
                            )
                        }
                    }
                }
            }
            Text(
                text = stringResource(R.string.center_rotary_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                color = UiPalette.MutedText,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ChevronIcon(
    modifier: Modifier,
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = Color.White,
        modifier = modifier.size(42.dp)
    )
}

@Composable
fun VerticalSwipeZone(
    modifier: Modifier,
    touchSlop: Float,
    onRotaryStep: (Int) -> Unit,
) {
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = modifier.pointerInput(touchSlop) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                var lastY = down.position.y
                var totalDragY = 0f
                var dragActive = false

                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break

                    val deltaY = change.position.y - lastY
                    lastY = change.position.y
                    totalDragY += deltaY

                    if (!dragActive && kotlin.math.abs(totalDragY) > touchSlop) {
                        dragActive = true
                    }

                    if (dragActive) {
                        change.consume()
                        dragAccumulator += deltaY
                        while (dragAccumulator <= -ROTARY_STEP_THRESHOLD) {
                            onRotaryStep(+1)
                            dragAccumulator += ROTARY_STEP_THRESHOLD
                        }
                        while (dragAccumulator >= ROTARY_STEP_THRESHOLD) {
                            onRotaryStep(-1)
                            dragAccumulator -= ROTARY_STEP_THRESHOLD
                        }
                    }
                }

                dragAccumulator = 0f
            }
        }
    )
}

private fun buildRingSegmentPath(
    center: Offset,
    outerRadius: Float,
    innerRadius: Float,
    startAngle: Float,
    sweepAngle: Float,
): Path {
    return Path().apply {
        arcTo(
            rect = Rect(
                left = center.x - outerRadius,
                top = center.y - outerRadius,
                right = center.x + outerRadius,
                bottom = center.y + outerRadius
            ),
            startAngleDegrees = startAngle,
            sweepAngleDegrees = sweepAngle,
            forceMoveTo = true
        )
        arcTo(
            rect = Rect(
                left = center.x - innerRadius,
                top = center.y - innerRadius,
                right = center.x + innerRadius,
                bottom = center.y + innerRadius
            ),
            startAngleDegrees = startAngle + sweepAngle,
            sweepAngleDegrees = -sweepAngle,
            forceMoveTo = false
        )
        close()
    }
}

@Composable
private fun HoldTouchZone(
    keyName: String,
    modifier: Modifier,
    onStartHold: (String) -> Unit,
    onStopHold: () -> Unit,
) {
    Box(
        modifier = modifier.pointerInput(keyName) {
            detectTapGestures(
                onPress = {
                    onStartHold(keyName)
                    tryAwaitRelease()
                    onStopHold()
                }
            )
        }
    )
}
