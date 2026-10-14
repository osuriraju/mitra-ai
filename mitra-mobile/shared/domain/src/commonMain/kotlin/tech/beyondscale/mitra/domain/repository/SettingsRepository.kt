package tech.beyondscale.mitra.domain.repository

import tech.beyondscale.mitra.domain.model.ModuleKey

interface SettingsRepository {
    /** Flips one module, read-modify-write against the stored settings so rapid toggles never drop each other. */
    suspend fun setModule(key: ModuleKey, enabled: Boolean)

    /** The account-level `shareWellness` (the AI context includes wellness when it or wellness `shareAI` is on). */
    suspend fun setShareWellness(enabled: Boolean)

    /** The account-level `quietHours` (10:30 PM – 7:00 AM): reminders inside them wait until 7:00. */
    suspend fun setQuietHours(enabled: Boolean)
}
