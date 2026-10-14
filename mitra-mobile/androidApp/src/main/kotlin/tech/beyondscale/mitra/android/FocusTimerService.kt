package tech.beyondscale.mitra.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import tech.beyondscale.mitra.feature.tasks.FocusKeepAlive

/** Keeps a running focus countdown alive in the background: ongoing countdown notification + Stop action. */
class FocusTimerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AndroidFocusKeepAlive.stopTaps.tryEmit(Unit)
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Focus"
        val endsAt = intent?.getLongExtra(EXTRA_ENDS_AT, 0L) ?: 0L
        val notification = notification(title, endsAt)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // E.g. ForegroundServiceStartNotAllowedException: the countdown then simply pauses with the screen.
            Log.w("MitraFocus", "couldn't start the focus timer service", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(title: String, endsAt: Long): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Focus timer", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) ?: Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FocusTimerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("Focus session")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setWhen(endsAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "focus-timer"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "tech.beyondscale.mitra.FOCUS_STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ENDS_AT = "endsAt"

        fun intent(context: Context, title: String, endsAt: Long) =
            Intent(context, FocusTimerService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_ENDS_AT, endsAt)
    }
}

class AndroidFocusKeepAlive(private val context: Context) : FocusKeepAlive {
    override fun start(title: String, endsAtEpochMs: Long) {
        try {
            context.startForegroundService(FocusTimerService.intent(context, title, endsAtEpochMs))
        } catch (e: Exception) {
            Log.w("MitraFocus", "couldn't start the focus timer service", e)
        }
    }

    override fun stop() {
        context.stopService(Intent(context, FocusTimerService::class.java))
    }

    override val stopRequests: SharedFlow<Unit> = stopTaps.asSharedFlow()

    internal companion object {
        val stopTaps = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }
}
