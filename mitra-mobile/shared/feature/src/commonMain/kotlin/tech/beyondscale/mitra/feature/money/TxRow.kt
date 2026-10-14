package tech.beyondscale.mitra.feature.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.fmtTime
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.designsystem.components.EmojiBox
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.domain.model.Account
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.model.TxSource

/** What one transaction row shows (web `TxR` in screens/money.tsx). */
data class TxRowUi(
    val id: String,
    val emoji: String,
    val title: String,
    val sub: String,
    val amount: String,
    val positive: Boolean,
    val accountLine: String,
    val recurring: Boolean,
    val ai: Boolean,
)

/** Port of `TxR`: merchant (or category) title, "Category · Today · 1:05 PM" (time only today/yesterday), signed amount. */
fun txRowUi(tx: Transaction, categories: List<Category>, accounts: List<Account>, today: String): TxRowUi {
    val cat = tx.categoryId?.let { id -> categories.firstOrNull { it.id == id } }
    val acc = accounts.firstOrNull { it.id == tx.accountId }
    val recent = tx.date == today || tx.date == addDays(today, -1)
    val day = fmtDay(tx.date, today) + if (recent) " · ${fmtTime(tx.time)}" else ""
    val income = tx.kind == TxKind.Income
    return TxRowUi(
        id = tx.id,
        emoji = cat?.emoji ?: "💸",
        title = tx.merchant.ifEmpty { cat?.name ?: tx.kind.label() },
        sub = if (cat != null) "${cat.name} · $day" else day,
        amount = (if (income) "+" else "−") + inrPaise(tx.amountPaise),
        positive = income,
        accountLine = acc?.let { "${it.name} ${it.mask}".trim() } ?: "",
        recurring = tx.source == TxSource.Recurring,
        ai = tx.source == TxSource.Ai,
    )
}

private fun TxKind.label() = when (this) {
    TxKind.Expense -> "Expense"
    TxKind.Income -> "Income"
    TxKind.Transfer -> "Transfer"
}

@Composable
fun TxRow(row: TxRowUi, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Edit transaction", onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmojiBox(row.emoji)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    row.title,
                    Modifier.weight(1f, fill = false),
                    style = Mitra.type.body.copy(fontWeight = FontWeight.Medium),
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.recurring) Icon(MitraIcons.Repeat, contentDescription = "Recurring", tint = c.faint, modifier = Modifier.size(12.dp))
                if (row.ai) AiTag()
            }
            Text(row.sub, style = Mitra.type.xs, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.amount, style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = if (row.positive) c.success else c.text, textAlign = TextAlign.End)
            if (row.accountLine.isNotEmpty()) Text(row.accountLine, style = Mitra.type.xs, color = c.muted, maxLines = 1)
        }
    }
}

@Composable
private fun AiTag() {
    val c = Mitra.colors
    Row(
        Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(MitraIcons.Sparkles, contentDescription = null, tint = c.accent, modifier = Modifier.size(11.dp))
        Text("AI", style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
    }
}
