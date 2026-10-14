package tech.beyondscale.mitra.designsystem.components

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

@Composable
fun MitraTopBar(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().height(56.dp).padding(horizontal = if (onBack == null) MitraDimens.pad else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(MitraIcons.ArrowLeft, contentDescription = "Back", tint = Mitra.colors.text) }
        Text(title, Modifier.weight(1f).semantics { heading() }, style = Mitra.type.h2, color = Mitra.colors.text)
        actions()
    }
}

@Composable
fun MitraSegmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(MitraDimens.radiusSm)).background(c.surface2).padding(3.dp).selectableGroup()) {
        options.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(MitraDimens.radiusSm - 2.dp))
                    .background(if (on) c.surface else c.surface2)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(i) })
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = if (on) c.text else c.muted, textAlign = TextAlign.Center)
            }
        }
    }
}
