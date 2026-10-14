package tech.beyondscale.mitra.domain.tasks

/** Vertical budget of the medium/large Tasks widget, in dp. The Glance layout draws with these same numbers. */
object TasksWidgetMetrics {
    const val PADDING = 12
    const val HEADER = 32
    const val BAR_GAP_TOP = 4
    const val BAR = 3
    const val BAR_GAP_BOTTOM = 6
    const val ROW = 36
    const val FOOTER = 20

    /** Everything above the first row: top + bottom padding, header, progress bar and its gaps. */
    const val CHROME = 2 * PADDING + HEADER + BAR_GAP_TOP + BAR + BAR_GAP_BOTTOM
}

/** [rows] task rows to draw; a "+[more] more" footer when [more] > 0. */
data class TasksWidgetLayout(val rows: Int, val more: Int)

/**
 * How many of [rowCount] rows fit in a widget [heightDp] tall. All of them when they fit; otherwise room is kept for
 * the "+N more" footer so it is never clipped. At least one row is always drawn; when even one row plus the footer
 * does not fit, the footer is dropped (the header still opens Tasks).
 */
fun tasksWidgetLayout(heightDp: Float, rowCount: Int): TasksWidgetLayout {
    if (rowCount <= 0) return TasksWidgetLayout(0, 0)
    val room = heightDp - TasksWidgetMetrics.CHROME
    if (rowCount <= (room / TasksWidgetMetrics.ROW).toInt()) return TasksWidgetLayout(rowCount, 0)
    val withFooter = ((room - TasksWidgetMetrics.FOOTER) / TasksWidgetMetrics.ROW).toInt()
    return if (withFooter >= 1) TasksWidgetLayout(withFooter, rowCount - withFooter) else TasksWidgetLayout(1, 0)
}
