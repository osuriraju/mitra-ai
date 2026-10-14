package tech.beyondscale.mitra.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.domain.model.ThemeMode
import tech.beyondscale.mitra.domain.notify.NotificationPrefs
import tech.beyondscale.mitra.domain.repository.PreferencesRepository

class PreferencesRepositoryImpl(private val prefs: PrefStore, private val io: CoroutineDispatcher = Dispatchers.IO) : PreferencesRepository {
    override val theme: Flow<ThemeMode> = prefs.observe(PrefKeys.THEME).map { v -> ThemeMode.entries.firstOrNull { it.name == v } ?: ThemeMode.Dark }
    override suspend fun setTheme(mode: ThemeMode) {
        withContext(io) { prefs.put(PrefKeys.THEME, mode.name) }
    }

    override val notificationPrefs: Flow<NotificationPrefs> = prefs.observe(PrefKeys.NOTIFY).map { v ->
        val f = v?.split(",").orEmpty()
        fun on(i: Int) = f.getOrNull(i) != "0"
        NotificationPrefs(tasks = on(0), habits = on(1), bills = on(2))
    }

    override suspend fun setNotificationPrefs(prefs: NotificationPrefs) {
        val v = listOf(prefs.tasks, prefs.habits, prefs.bills).joinToString(",") { if (it) "1" else "0" }
        withContext(io) { this@PreferencesRepositoryImpl.prefs.put(PrefKeys.NOTIFY, v) }
    }
}
