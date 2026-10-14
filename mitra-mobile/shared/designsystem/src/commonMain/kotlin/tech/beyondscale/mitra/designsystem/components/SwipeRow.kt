package tech.beyondscale.mitra.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import kotlin.math.roundToInt

enum class SwipeOutcome { None, Right, Left }

/** Resolves a released drag; an unmeasured row (width 0) never triggers. */
fun swipeOutcome(offsetPx: Float, widthPx: Float, threshold: Float = 0.35f): SwipeOutcome = when {
    widthPx <= 0f -> SwipeOutcome.None
    offsetPx > widthPx * threshold -> SwipeOutcome.Right
    offsetPx < -widthPx * threshold -> SwipeOutcome.Left
    else -> SwipeOutcome.None
}

@Composable
fun SwipeRow(
    onSwipeRight: (() -> Unit)?,
    onSwipeLeft: (() -> Unit)?,
    rightLabel: String,
    rightIcon: ImageVector,
    leftLabel: String,
    leftIcon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = Mitra.colors
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var width by remember { mutableIntStateOf(0) }
    val right by rememberUpdatedState(onSwipeRight)
    val left by rememberUpdatedState(onSwipeLeft)
    val shape = RoundedCornerShape(MitraDimens.radius)

    Box(modifier.clip(shape).onSizeChanged { width = it.width }) {
        val o = offset.value
        if (o != 0f) {
            val toRight = o > 0f
            Row(
                Modifier.matchParentSize()
                    .background(if (toRight) c.successSoft else c.warningSoft)
                    .padding(horizontal = MitraDimens.pad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toRight) Arrangement.Start else Arrangement.End,
            ) {
                val tint = if (toRight) c.success else c.warning
                Icon(if (toRight) rightIcon else leftIcon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Text(
                    if (toRight) rightLabel else leftLabel,
                    style = Mitra.type.sm, color = tint, modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        Box(
            Modifier
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var crossed = false
                    detectHorizontalDragGestures(
                        onDragStart = { crossed = false },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                        onDragEnd = {
                            val w = width.toFloat()
                            val outcome = swipeOutcome(offset.value, w)
                            val cb = when (outcome) {
                                SwipeOutcome.Right -> right
                                SwipeOutcome.Left -> left
                                SwipeOutcome.None -> null
                            }
                            scope.launch {
                                if (cb != null) {
                                    offset.animateTo(if (outcome == SwipeOutcome.Right) w else -w)
                                    cb()
                                    offset.snapTo(0f)
                                } else {
                                    offset.animateTo(0f)
                                }
                            }
                        },
                    ) { _, dragAmount ->
                        val next = offset.value + dragAmount
                        val allowed = (next > 0f && right != null) || (next < 0f && left != null)
                        val target = if (allowed) next else 0f
                        scope.launch { offset.snapTo(target) }
                        val past = swipeOutcome(target, width.toFloat()) != SwipeOutcome.None
                        if (past && !crossed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        crossed = past
                    }
                }
                .offset { IntOffset(offset.value.roundToInt(), 0) },
        ) { content() }
    }
}
