package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

/** `₹` plus an already-formatted amount in large tabular numerals. */
@Composable
fun AmountDisplay(text: String, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    val shown = text.ifEmpty { "0" }
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Amount, ₹$shown" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("₹", style = Mitra.type.numLg.copy(fontSize = 28.sp), color = c.muted)
        Text(
            shown,
            style = Mitra.type.numLg.copy(fontSize = 44.sp, fontWeight = FontWeight.SemiBold),
            color = if (text.isEmpty()) c.faint else c.text,
        )
    }
}

private val Rows = listOf("123", "456", "789")

@Composable
fun AmountKeypad(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val tick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ch -> Key(ch.toString(), "$ch", onClick = { tick(); onKey(ch) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(".", "Decimal point", onClick = { tick(); onKey('.') })
            Key("0", "0", onClick = { tick(); onKey('0') })
            Key(
                "⌫", "Delete", onClick = { tick(); onBackspace() },
                onLongClick = onClear?.let { clear -> { tick(); clear() } },
                longClickLabel = "Clear amount",
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.Key(
    label: String,
    description: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val c = Mitra.colors
    Box(
        Modifier.weight(1f).heightIn(min = 56.dp)
            .clip(RoundedCornerShape(MitraDimens.radius)).background(c.surface2)
            .combinedClickable(
                role = Role.Button, onClickLabel = null, onClick = onClick,
                onLongClickLabel = longClickLabel, onLongClick = onLongClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Mitra.type.numLg, color = c.text)
    }
}
