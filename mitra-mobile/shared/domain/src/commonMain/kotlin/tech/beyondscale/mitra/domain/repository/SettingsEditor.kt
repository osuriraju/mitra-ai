package tech.beyondscale.mitra.domain.repository

/** The remaining account settings of the web Settings screen, queued (offline-first) like [SettingsRepository]. */
interface SettingsEditor {
    suspend fun update(change: SettingChange)
}

/** A single account-setting edit; each maps to one key of the server's settings document. */
sealed interface SettingChange {
    data class TodaySections(val keys: List<String>) : SettingChange
    data class WeekStart(val day: String) : SettingChange
    data class DayStart(val hhmm: String) : SettingChange
    data class Digest(val on: Boolean) : SettingChange
    data class AutoSave(val mode: String) : SettingChange
    data class FocusMinutes(val minutes: Int) : SettingChange
}
