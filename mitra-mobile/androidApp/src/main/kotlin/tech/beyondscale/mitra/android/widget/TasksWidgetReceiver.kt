package tech.beyondscale.mitra.android.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()

    /** First Tasks widget placed: start the midnight redraws. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        MidnightReceiver.schedule(context)
    }

    /** Last Tasks widget removed: nothing left to redraw at midnight. */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        MidnightReceiver.cancel(context)
    }
}
