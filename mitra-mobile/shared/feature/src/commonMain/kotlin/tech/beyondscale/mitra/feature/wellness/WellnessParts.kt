package tech.beyondscale.mitra.feature.wellness

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.beyondscale.mitra.designsystem.components.IconBox
import tech.beyondscale.mitra.designsystem.components.Tone
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.MOODS

private val MOOD_NAMES = listOf("Awful", "Low", "Okay", "Good", "Great")

/** Five-point mood picker (web `MoodRow`); tapping the selected mood clears it. Reusable on Today. */
@Composable
fun MoodRow(selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MOODS.forEachIndexed { i, emoji ->
            val on = selected == i
            val shape = RoundedCornerShape(MitraDimens.radiusSm)
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                    .background(if (on) c.accentSoft else c.surface2)
                    .border(1.dp, if (on) c.accent else c.border, shape)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(i) })
                    .semantics { contentDescription = "Mood ${MOOD_NAMES[i]}" },
                contentAlignment = Alignment.Center,
            ) { Text(emoji, fontSize = 22.sp) }
        }
    }
}

@Composable
internal fun FieldLabel(text: String) {
    Text(text, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.muted)
}

/** A tappable "HH:MM" field that opens the time picker. */
@Composable
internal fun TimeField(label: String, value: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    val shape = RoundedCornerShape(MitraDimens.radiusSm)
    Row(
        modifier.heightIn(min = 48.dp).clip(shape).border(1.dp, c.border, shape)
            .clickable(role = Role.Button, onClickLabel = "Change $label", onClick = onClick)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label $value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
        Text(value, style = Mitra.type.num, color = c.text)
    }
}

/** − value + (web `Stepper`). */
@Composable
internal fun Stepper(value: Int, onChange: (Int) -> Unit, max: Int, label: String, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    val shape = RoundedCornerShape(MitraDimens.radiusSm)
    Row(
        modifier.height(48.dp).clip(shape).border(1.dp, c.border, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onChange(value - 1) }, enabled = value > 0) { Icon(MitraIcons.Minus, "Fewer $label", tint = c.muted) }
        Text(value.toString(), Modifier.weight(1f), style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = c.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange(value + 1) }, enabled = value < max) { Icon(MitraIcons.Plus, "More $label", tint = c.accent) }
    }
}

@Composable
internal fun Kpi(label: String, value: String, sub: String?, up: Boolean? = null, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = c.muted, maxLines = 1)
        Text(value, style = Mitra.type.numLg, color = c.text, maxLines = 1)
        if (!sub.isNullOrEmpty()) Text(sub, style = Mitra.type.xs, color = when (up) { true -> c.success; false -> c.danger; null -> c.faint })
    }
}

/** A list row with an icon, title, subtitle and a right-hand value (web `.lrow`). */
@Composable
internal fun StatRow(icon: ImageVector, title: String, sub: String?, value: String, tone: Tone = Tone.Neutral, onClick: (() -> Unit)? = null) {
    val c = Mitra.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBox(icon, tone = tone, size = 32.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = Mitra.type.body, color = c.text, maxLines = 2)
            if (!sub.isNullOrEmpty()) Text(sub, style = Mitra.type.xs, color = c.muted)
        }
        Text(value, style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = c.text)
    }
}
