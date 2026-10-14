package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra

@Composable
fun CheckCircle(checked: Boolean, onToggle: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(if (checked) c.accent else androidx.compose.ui.graphics.Color.Transparent)
                .border(1.5.dp, if (checked) c.accent else c.faint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(MitraIcons.Check, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(14.dp))
        }
    }
}
