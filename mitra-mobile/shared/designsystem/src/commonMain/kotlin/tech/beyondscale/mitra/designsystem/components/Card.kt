package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

enum class CardTone { Default, Soft, Accent }

@Composable
fun MitraCard(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.Default,
    contentPadding: PaddingValues = PaddingValues(MitraDimens.pad),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Mitra.colors
    val (bg, border) = when (tone) {
        CardTone.Default -> c.surface to c.border
        CardTone.Soft -> c.surface2 to Color.Transparent
        CardTone.Accent -> c.accentSoft to c.accent.copy(alpha = 0.25f)
    }
    val shape = RoundedCornerShape(MitraDimens.radius)
    Column(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
