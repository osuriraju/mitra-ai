package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra

enum class ChipTone { Neutral, Accent, Success, Danger, Warning, Info, Outline }

@Composable
fun MitraChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.Neutral,
    icon: ImageVector? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val c = Mitra.colors
    val (bg, fg, border) = when {
        selected -> Triple(c.accentSoft, c.accent, c.accent.copy(alpha = 0.4f))
        else -> when (tone) {
            ChipTone.Neutral -> Triple(c.surface2, c.muted, Color.Transparent)
            ChipTone.Accent -> Triple(c.accentSoft, c.accent, Color.Transparent)
            ChipTone.Success -> Triple(c.successSoft, c.success, Color.Transparent)
            ChipTone.Danger -> Triple(c.dangerSoft, c.danger, Color.Transparent)
            ChipTone.Warning -> Triple(c.warningSoft, c.warning, Color.Transparent)
            ChipTone.Info -> Triple(lerpSoft(c.surface, c.info), c.info, Color.Transparent)
            ChipTone.Outline -> Triple(Color.Transparent, c.muted, c.border)
        }
    }
    // Wrap with a 48dp touch target without growing the visible pill.
    Box(
        modifier
            .then(
                if (onClick != null) {
                    Modifier.minimumInteractiveComponentSize()
                        .semantics { this.selected = selected }
                        .clickable(role = Role.Button, onClick = onClick)
                } else Modifier,
            )
            // Remove is also a custom action so it stays reachable when the chip merges its children.
            .semantics(mergeDescendants = onClick != null || onRemove != null) {
                if (onRemove != null) customActions = listOf(CustomAccessibilityAction("Remove $text") { onRemove(); true })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.padding(end = if (onRemove != null) 7.dp else 0.dp), contentAlignment = Alignment.CenterStart) {
            Row(
                Modifier.heightIn(min = 32.dp).clip(CircleShape).background(bg).border(1.dp, border, CircleShape)
                    .padding(start = 10.dp, end = if (onRemove != null) 27.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
                Text(text, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = fg)
            }
            if (onRemove != null) {
                // 48dp touch area centred on a 14dp glyph; visual chip height stays 32dp.
                Box(
                    Modifier.align(Alignment.CenterEnd).offset(x = 7.dp).size(48.dp).clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = "Remove $text", onClick = onRemove)
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) { Icon(MitraIcons.X, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp)) }
            }
        }
    }
}

private fun lerpSoft(surface: Color, tint: Color): Color = androidx.compose.ui.graphics.lerp(surface, tint, 0.14f)
