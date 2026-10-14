package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra

@Composable
fun MonthSwitcher(
    title: String,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Mitra.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = onPrev, modifier = Modifier.size(48.dp)) {
            Icon(MitraIcons.ChevronLeft, contentDescription = "Previous month", tint = c.text)
        }
        Text(title, Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }, style = Mitra.type.h3, color = c.text)
        IconButton(onClick = onNext, enabled = canNext, modifier = Modifier.size(48.dp)) {
            Icon(MitraIcons.ChevronRight, contentDescription = "Next month", tint = if (canNext) c.text else c.faint)
        }
    }
}
