package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, style = Mitra.type.eyebrow, color = Mitra.colors.muted)

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) =
    Text(title, modifier.padding(top = 4.dp), style = Mitra.type.h3, color = Mitra.colors.text)

/** The app's logo mark: accent tile with an "M". */
@Composable
fun MitraMark(size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Mitra.colors.accent),
        contentAlignment = Alignment.Center,
    ) { Text("M", color = Mitra.colors.onAccent, style = Mitra.type.h1.copy(fontSize = (size.value * 0.5f).sp)) }
}

@Composable
fun Avatar(initials: String, size: Dp = 32.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Mitra.colors.accentSoft), contentAlignment = Alignment.Center) {
        Text(initials, style = Mitra.type.xs.copy(fontWeight = FontWeight.Bold), color = Mitra.colors.accent)
    }
}

@Composable
fun StatCard(label: String, value: String, sub: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    MitraCard(modifier.width(148.dp), onClick = onClick) {
        Text(label, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.muted)
        Text(value, style = Mitra.type.numLg, color = Mitra.colors.text)
        Text(sub, style = Mitra.type.xs, color = Mitra.colors.faint)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = MitraDimens.pad), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Mitra.colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Mitra.colors.accent)
        }
        Text(title, style = Mitra.type.h3, color = Mitra.colors.text, textAlign = TextAlign.Center)
        Text(body, style = Mitra.type.sm, color = Mitra.colors.muted, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

enum class BannerTone { Danger, Info }

@Composable
fun MitraBanner(text: String, tone: BannerTone = BannerTone.Danger, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    val (bg, fg) = if (tone == BannerTone.Danger) c.dangerSoft to c.danger else c.accentSoft to c.accent
    Text(text, modifier.fillMaxWidth().clip(RoundedCornerShape(MitraDimens.radiusSm)).background(bg).padding(12.dp), style = Mitra.type.sm, color = fg)
}

enum class PillTone { Neutral, Warning, Accent }

@Composable
fun SyncPill(text: String, tone: PillTone, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    val dot = when (tone) { PillTone.Neutral -> c.muted; PillTone.Warning -> c.warning; PillTone.Accent -> c.accent }
    Row(
        modifier.clip(CircleShape).background(c.surface2).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
    }
}

@Composable
fun MitraSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, sub: String? = null, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Mitra.type.bodyBold, color = c.text)
            if (sub != null) Text(sub, style = Mitra.type.xs, color = c.muted)
        }
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.track, uncheckedBorderColor = c.border, uncheckedThumbColor = c.muted),
        )
    }
}
