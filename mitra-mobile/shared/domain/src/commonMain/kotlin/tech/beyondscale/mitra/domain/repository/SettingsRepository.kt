package tech.beyondscale.mitra.domain.repository

import tech.beyondscale.mitra.domain.model.Modules

interface SettingsRepository {
    suspend fun updateModules(modules: Modules)
}
