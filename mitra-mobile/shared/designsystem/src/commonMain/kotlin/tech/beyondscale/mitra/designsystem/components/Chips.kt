package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra

enum class ChipTone { Neutral, Accent, Danger, Warning, Info, Outline }

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
            ChipTone.Danger -> Triple(c.dangerSoft, c.danger, Color.Transparent)
            ChipTone.Warning -> Triple(c.warningSoft, c.warning, Color.Transparent)
            ChipTone.Info -> Triple(lerpSoft(c.surface, c.info), c.info, Color.Transparent)
            ChipTone.Outline -> Triple(Color.Transparent, c.muted, c.border)
        }
    }
    Box(
        modifier
            .then(
                if (onClick != null) {
                    Modifier.minimumInteractiveComponentSize()
                        .semantics { this.selected = selected }
                        .clickable(role = Role.Button, onClick = onClick)
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).clip(CircleShape).background(bg).border(1.dp, border, CircleShape)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Text(text, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = fg)
            if (onRemove != null) {
                Icon(
                    MitraIcons.X, contentDescription = "Remove $text", tint = fg,
                    modifier = Modifier.size(14.dp).clickable(role = Role.Button, onClick = onRemove),
                )
            }
        }
    }
}

private fun lerpSoft(surface: Color, tint: Color): Color = androidx.compose.ui.graphics.lerp(surface, tint, 0.14f)
