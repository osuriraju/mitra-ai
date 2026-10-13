package tech.beyondscale.mitra.domain.repository

import tech.beyondscale.mitra.domain.model.ModuleKey

interface SettingsRepository {
    /** Flips one module, read-modify-write against the stored settings so rapid toggles never drop each other. */
    suspend fun setModule(key: ModuleKey, enabled: Boolean)
}
