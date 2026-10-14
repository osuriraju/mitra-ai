package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.domain.notify.NotificationPrefs

/** Device-local preferences (not synced). */
interface PreferencesRepository {
    val theme: Flow<ThemeMode>
    suspend fun setTheme(mode: ThemeMode)
    /** Which reminder types this device shows. */
    val notificationPrefs: Flow<NotificationPrefs>
    suspend fun setNotificationPrefs(prefs: NotificationPrefs)
}
