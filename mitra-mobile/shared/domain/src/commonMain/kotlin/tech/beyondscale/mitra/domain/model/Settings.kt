package tech.beyondscale.mitra.domain.model

data class Modules(val money: Boolean = true, val wellness: Boolean = true, val ai: Boolean = true) {
    fun with(key: ModuleKey, enabled: Boolean): Modules = when (key) {
        ModuleKey.Money -> copy(money = enabled)
        ModuleKey.Wellness -> copy(wellness = enabled)
        ModuleKey.Ai -> copy(ai = enabled)
    }
}

enum class ModuleKey { Money, Wellness, Ai }

/** Mirrors `Settings` in mitra-frontend/src/store/types.ts; defaults mirror store/seed.ts SETTINGS. */
data class Settings(
    val reduceMotion: Boolean = false,
    val compact: Boolean = false,
    val modules: Modules = Modules(),
    val todaySections: List<String> = DEFAULT_TODAY_SECTIONS,
    val weekStart: String = "Monday",
    val dayStart: String = "06:00",
    val quietHours: Boolean = true,
    val digest: Boolean = true,
    val autoSave: String = "Small only",
    val shareWellness: Boolean = false,
    val focusMinutes: Int = 25,
) {
    companion object {
        val DEFAULT_TODAY_SECTIONS = listOf("priorities", "activity", "habits", "checkin", "money", "goals")
    }
}
