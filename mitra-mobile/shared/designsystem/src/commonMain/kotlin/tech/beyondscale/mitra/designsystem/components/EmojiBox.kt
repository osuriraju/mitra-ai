package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.beyondscale.mitra.designsystem.theme.Mitra

/** Rounded soft box holding an emoji (web `EmojiBox`). Decorative: the row's text carries the meaning. */
@Composable
fun EmojiBox(emoji: String, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.29f)).background(Mitra.colors.surface2),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = (size.value * 0.5f).sp)
    }
}

/** Categorical chart colours (`--s1…--s8`), theme-aware. */
val SeriesColors: List<androidx.compose.ui.graphics.Color>
    @Composable get() = Mitra.colors.series

/** Colour for the aggregated "Other" slice. */
val OtherColor: androidx.compose.ui.graphics.Color
    @Composable get() = Mitra.colors.faint
