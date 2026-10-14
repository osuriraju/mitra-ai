package tech.beyondscale.mitra.feature.money

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.core.inrShortPaise
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.ScrubBars
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.Cycle
import tech.beyondscale.mitra.feature.common.EventEffect
import tech.beyondscale.mitra.feature.tasks.OverflowItem

@Composable
fun SubscriptionsRoute(onBack: () -> Unit, vm: SubscriptionsViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    SubscriptionsScreen(state, vm::onAction, onBack)
}

/** Subscriptions tracker (web `SubscriptionsScreen`). */
@Composable
fun SubscriptionsScreen(state: SubscriptionsUiState, onAction: (SubscriptionsAction) -> Unit, onBack: () -> Unit) {
    val c = Mitra.colors
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Subscriptions", onBack = onBack) {
            IconButton(onClick = { adding = true }) { Icon(MitraIcons.Plus, contentDescription = "Add subscription", tint = c.accent) }
        }
        if (!state.loaded) return@Column
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = MitraDimens.pad, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            item(key = "kpis") {
                MitraCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RecSubKpi("Monthly", state.monthly, state.monthlySub, Modifier.weight(1f))
                        RecSubKpi("Yearly equivalent", state.yearly, null, Modifier.weight(1f))
                        RecSubKpi("Unused 30d", state.unusedCount, state.unusedSub, Modifier.weight(1f))
                    }
                }
            }
            item(key = "list") {
                MitraCard(contentPadding = PaddingValues(0.dp)) {
                    state.rows.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = c.border)
                        SubRow(
                            r,
                            onUsed = { onAction(SubscriptionsAction.MarkUsed(r.id)) },
                            onGuidance = { onAction(SubscriptionsAction.CancelGuidance) },
                            onRemove = { deleteId = r.id },
                        )
                    }
                }
            }
            item(key = "renewHeader") { SectionHeader("Renewals · next 30 days") }
            item(key = "renewals") {
                MitraCard {
                    if (state.renewalPaise.isEmpty()) {
                        Text("Nothing renews in the next 30 days.", style = Mitra.type.body, color = c.muted)
                    } else {
                        ScrubBars(
                            values = state.renewalPaise,
                            labels = state.renewalLabels,
                            format = ::inrShortPaise,
                            modifier = Modifier.height(130.dp),
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        AddSubscriptionSheet(
            onSave = { name, paise, cycle -> onAction(SubscriptionsAction.Add(name, paise, cycle)) },
            onDismiss = { adding = false },
        )
    }
    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Remove this subscription?", style = Mitra.type.h3, color = c.text) },
            text = { Text("This only removes it from tracking.", style = Mitra.type.body, color = c.muted) },
            confirmButton = {
                TextButton(onClick = { deleteId = null; onAction(SubscriptionsAction.Delete(id)) }) { Text("Remove", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel", color = c.muted) } },
            containerColor = c.surface,
        )
    }
}

@Composable
private fun SubRow(r: SubscriptionRowUi, onUsed: () -> Unit, onGuidance: () -> Unit, onRemove: () -> Unit) {
    val c = Mitra.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(parseHex(r.color, c.accent)).clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) { Text(r.letter, style = Mitra.type.bodyBold, color = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(r.name, style = Mitra.type.bodyBold, color = c.text)
            Text(r.sub, style = Mitra.type.xs, color = c.muted)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.amount, style = Mitra.type.num, color = c.text)
            if (r.unusedChip != null) MitraChip(r.unusedChip, tone = ChipTone.Warning)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(MitraIcons.More, contentDescription = "More actions for ${r.name}", tint = c.muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                OverflowItem("Mark as used today", MitraIcons.Check) { menu = false; onUsed() }
                OverflowItem("Cancel guidance", MitraIcons.External) { menu = false; onGuidance() }
                OverflowItem("Remove", MitraIcons.Trash, danger = true) { menu = false; onRemove() }
            }
        }
    }
}

/** "#RRGGBB" → Color; anything else falls back. */
internal fun parseHex(hex: String, fallback: Color): Color {
    val h = hex.removePrefix("#")
    val v = h.toLongOrNull(16) ?: return fallback
    return if (h.length == 6) Color((0xFF000000L or v).toInt()) else fallback
}

@Composable
private fun AddSubscriptionSheet(onSave: (String, Long, Cycle) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var cycle by rememberSaveable { mutableStateOf(0) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    RecSubSheet(
        title = "Add subscription",
        onSave = {
            val rupees = amount.toLongOrNull() ?: 0L
            if (name.isBlank() || rupees <= 0) {
                error = if (name.isBlank()) "Enter a service name" else "Enter an amount"
                false
            } else {
                onSave(name.trim(), rupees * 100, Cycle.entries[cycle])
                true
            }
        },
        onDismiss = onDismiss,
    ) {
        MitraTextField(name, { name = it; error = null }, "Service name", placeholder = "Service name", error = error?.takeIf { name.isBlank() })
        MitraTextField(
            amount, { amount = digitsOnly(it); error = null }, "Amount (₹)", placeholder = "Amount",
            keyboardType = KeyboardType.Number, error = error?.takeIf { name.isNotBlank() },
        )
        MitraSegmented(Cycle.entries.map { it.wire }, cycle, { cycle = it })
    }
}
