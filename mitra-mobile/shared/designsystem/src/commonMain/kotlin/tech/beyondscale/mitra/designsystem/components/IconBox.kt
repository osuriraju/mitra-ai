package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.theme.Mitra

/** Rounded soft box holding an icon (web `IconBox`). Decorative: the row's text carries the meaning. */
@Composable
fun IconBox(icon: ImageVector, tone: Tone = Tone.Neutral, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    val bg = when (tone) {
        Tone.Neutral -> c.surface2
        Tone.Accent -> c.accentSoft
        Tone.Success -> c.successSoft
        Tone.Warning -> c.warningSoft
        Tone.Danger -> c.dangerSoft
    }
    Box(
        modifier.size(size).clearAndSetSemantics { }.clip(RoundedCornerShape(size * 0.29f)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        // Web: 19px glyph in a 38px box.
        Icon(icon, contentDescription = null, tint = tone.color(), modifier = Modifier.size(size * 0.5f))
    }
}
