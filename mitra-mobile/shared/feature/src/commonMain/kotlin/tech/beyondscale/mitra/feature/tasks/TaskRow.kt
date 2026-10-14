package tech.beyondscale.mitra.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.core.minsToHm
import tech.beyondscale.mitra.designsystem.components.CheckCircle
import tech.beyondscale.mitra.designsystem.components.SwipeRow
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.tasks.dueLabel
import tech.beyondscale.mitra.domain.tasks.isOverdue
import tech.beyondscale.mitra.domain.tasks.subProgress

/** What one task row shows (web `TaskRowVM`). */
data class TaskRowUi(
    val id: String,
    val title: String,
    val done: Boolean,
    val overdue: Boolean,
    val due: String?,
    val project: String?,
    val est: String?,
    val sub: String?,
    val recurring: Boolean,
    val ai: Boolean,
    val parent: String?,
    val pri: Priority?,
)

/** Port of `taskVM` in mitra-frontend/src/screens/vm.ts. */
internal fun taskRowUi(t: Task, all: List<Task>, projects: Map<String, Project>, today: String, showProject: Boolean = true): TaskRowUi {
    val sub = subProgress(all, t.id)
    return TaskRowUi(
        id = t.id,
        title = t.title,
        done = t.done,
        overdue = t.isOverdue(today),
        due = dueLabel(t, today),
        project = if (showProject) t.projectId?.let { projects[it]?.name } else null,
        est = t.estMin?.takeIf { it > 0 }?.let(::minsToHm),
        sub = if (sub.total > 0) "${sub.done}/${sub.total}" else null,
        recurring = t.recurring != null,
        ai = t.source == "ai",
        parent = t.parentId?.let { pid -> all.firstOrNull { it.id == pid }?.title },
        pri = t.pri,
    )
}

@Composable
fun TaskRow(
    row: TaskRowUi,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    val c = Mitra.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected) c.accentSoft else c.surface)
            .combinedClickable(role = Role.Button, onClickLabel = "Open task", onLongClickLabel = onLongPress?.let { "Select task" }, onLongClick = onLongPress, onClick = onOpen)
            .heightIn(min = 56.dp)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(
            checked = row.done,
            onToggle = onToggle,
            label = if (row.done) "Mark ${row.title} not done" else "Complete ${row.title}",
        )
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                row.title,
                style = Mitra.type.body.copy(fontWeight = FontWeight.Medium, textDecoration = if (row.done) TextDecoration.LineThrough else null),
                color = if (row.done) c.muted else c.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TaskMeta(row)
        }
        Spacer(Modifier.width(8.dp))
        PriorityBar(row.pri)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskMeta(row: TaskRowUi) {
    val c = Mitra.colors
    val parts = buildList<@Composable () -> Unit> {
        row.due?.let { due ->
            add {
                val tint = if (row.overdue) c.danger else c.muted
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(MitraIcons.Clock, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
                    Text(due, style = Mitra.type.xs.copy(fontWeight = if (row.overdue) FontWeight.SemiBold else null), color = tint)
                }
            }
        }
        row.project?.let { add { MetaText(it) } }
        row.est?.let { add { MetaText(it) } }
        row.sub?.let { sub ->
            add {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(MitraIcons.CheckSquare, contentDescription = "Subtasks", tint = c.muted, modifier = Modifier.size(12.dp))
                    MetaText(sub)
                }
            }
        }
        if (row.recurring) add { Icon(MitraIcons.Repeat, contentDescription = "Repeats", tint = c.muted, modifier = Modifier.size(12.dp)) }
    }
    if (parts.isEmpty() && !row.ai && row.parent == null) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, part ->
            if (i > 0) MetaText("·")
            part()
        }
        if (row.ai) MetaTag("AI", c.accent, c.accentSoft, MitraIcons.Sparkles)
        if (row.parent != null) {
            val p = if (row.parent.length > 26) row.parent.take(24) + "…" else row.parent
            MetaTag("↳ $p", c.muted, Color.Transparent, border = true, description = "Subtask of ${row.parent}")
        }
    }
}

@Composable
private fun MetaText(text: String) = Text(text, style = Mitra.type.xs, color = Mitra.colors.muted)

@Composable
private fun MetaTag(text: String, fg: Color, bg: Color, icon: ImageVector? = null, border: Boolean = false, description: String? = null) {
    val shape = CircleShape
    Row(
        Modifier.clip(shape).background(bg)
            .then(if (border) Modifier.border(1.dp, Mitra.colors.border, shape) else Modifier)
            .padding(horizontal = 7.dp, vertical = 1.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(11.dp))
        Text(text, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

@Composable
private fun PriorityBar(pri: Priority?) {
    val c = Mitra.colors
    val color = when (pri) {
        Priority.High -> c.danger
        Priority.Med -> c.warning
        Priority.Low -> c.info
        null -> Color.Transparent
    }
    Box(
        Modifier.size(width = 4.dp, height = 28.dp).clip(RoundedCornerShape(2.dp)).background(color)
            .then(if (pri != null) Modifier.semantics { contentDescription = "${pri.label} priority" } else Modifier),
    )
}

/** [TaskRow] with swipe-right to complete (open tasks only) and swipe-left to move to tomorrow. */
@Composable
fun SwipeTaskRow(
    row: TaskRowUi,
    onToggle: () -> Unit,
    onReschedule: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    SwipeRow(
        onSwipeRight = if (!row.done) onToggle else null,
        onSwipeLeft = onReschedule,
        rightLabel = "Complete",
        rightIcon = MitraIcons.Check,
        leftLabel = "Tomorrow",
        leftIcon = MitraIcons.Clock,
        modifier = modifier,
    ) {
        TaskRow(row, onToggle, onOpen, onLongPress = onLongPress, selected = selected)
    }
}
