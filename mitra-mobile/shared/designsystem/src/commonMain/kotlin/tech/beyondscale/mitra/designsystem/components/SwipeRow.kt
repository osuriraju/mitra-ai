package tech.beyondscale.mitra.designsystem.components

import androidx.compose.animation.core.animate
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
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
    var shown by remember { mutableFloatStateOf(0f) }
    var settle by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var width by remember { mutableIntStateOf(0) }
    val right by rememberUpdatedState(onSwipeRight)
    val left by rememberUpdatedState(onSwipeLeft)
    val shape = RoundedCornerShape(MitraDimens.radius)

    fun settleTo(target: Float, then: () -> Unit = {}) {
        settle?.cancel()
        settle = scope.launch {
            animate(shown, target) { v, _ -> shown = v }
            then()
        }
    }

    Box(modifier.clip(shape).onSizeChanged { width = it.width }) {
        val o = shown
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
                .semantics {
                    // Swipe is not discoverable with TalkBack/switch access: expose both actions.
                    customActions = if (!enabled) emptyList() else listOfNotNull(
                        onSwipeRight?.let { CustomAccessibilityAction(rightLabel) { right?.invoke(); true } },
                        onSwipeLeft?.let { CustomAccessibilityAction(leftLabel) { left?.invoke(); true } },
                    )
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    // Synchronous accumulator: never re-read from the animated value.
                    var raw = 0f
                    var crossed = false
                    detectHorizontalDragGestures(
                        onDragStart = {
                            settle?.cancel()
                            raw = shown
                            crossed = false
                        },
                        onDragCancel = { raw = 0f; settleTo(0f) },
                        onDragEnd = {
                            val w = width.toFloat()
                            val outcome = swipeOutcome(raw, w)
                            val cb = when (outcome) {
                                SwipeOutcome.Right -> right
                                SwipeOutcome.Left -> left
                                SwipeOutcome.None -> null
                            }
                            raw = 0f
                            if (cb != null) {
                                settleTo(if (outcome == SwipeOutcome.Right) w else -w) {
                                    cb()
                                    shown = 0f
                                }
                            } else {
                                settleTo(0f)
                            }
                        },
                    ) { _, dragAmount ->
                        raw = clampDrag(raw + dragAmount, right != null, left != null, width.toFloat())
                        shown = raw
                        val past = swipeOutcome(raw, width.toFloat()) != SwipeOutcome.None
                        if (past && !crossed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        crossed = past
                    }
                }
                .offset { IntOffset(shown.roundToInt(), 0) },
        ) { content() }
    }
}

/** Keeps a drag inside the row and only in directions that have a callback. */
fun clampDrag(raw: Float, canRight: Boolean, canLeft: Boolean, widthPx: Float): Float {
    val max = if (canRight) widthPx else 0f
    val min = if (canLeft) -widthPx else 0f
    return raw.coerceIn(min, max.coerceAtLeast(min))
}
