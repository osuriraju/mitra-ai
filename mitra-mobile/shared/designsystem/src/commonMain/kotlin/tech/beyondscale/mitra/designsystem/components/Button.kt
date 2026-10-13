package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

enum class ButtonKind { Primary, Outline, Ghost, Soft, Danger }
enum class ButtonSize { Md, Lg }

@Composable
fun MitraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    size: ButtonSize = ButtonSize.Md,
    icon: ImageVector? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Mitra.colors
    val (bg, fg, border) = when (kind) {
        ButtonKind.Primary -> Triple(c.accent, c.onAccent, Color.Transparent)
        ButtonKind.Outline -> Triple(Color.Transparent, c.text, c.border)
        ButtonKind.Ghost -> Triple(Color.Transparent, c.muted, Color.Transparent)
        ButtonKind.Soft -> Triple(c.accentSoft, c.accent, Color.Transparent)
        ButtonKind.Danger -> Triple(c.dangerSoft, c.danger, Color.Transparent)
    }
    val shape = RoundedCornerShape(MitraDimens.radiusSm)
    Row(
        modifier
            .heightIn(min = if (size == ButtonSize.Lg) 52.dp else 48.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
        else if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = fg)
        Text(text, style = Mitra.type.bodyBold, color = fg)
    }
}
