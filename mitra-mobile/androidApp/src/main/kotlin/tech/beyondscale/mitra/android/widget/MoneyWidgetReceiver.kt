package tech.beyondscale.mitra.android.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class MoneyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MoneyWidget()

    /** First Money widget placed: safe-to-spend changes at midnight, so start the midnight redraws. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        MidnightReceiver.schedule(context)
    }

    /** Last Money widget removed: keep the alarm only while a Tasks widget still needs it. */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        MidnightReceiver.rescheduleAsync(context, goAsync())
    }
}
