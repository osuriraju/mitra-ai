package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.model.User

/** Shape of `toPublic()` in mitra-backend/src/users/users.service.ts. */
@Serializable
data class UserDto(
    val id: String,
    val name: String,
    val email: String,
    val username: String? = null,
    val phone: String? = null,
    val settings: JsonObject = JsonObject(emptyMap()),
    val onboardedAt: String? = null,
    val memberSince: String = "",
)

@Serializable
data class UserEnvelope(val user: UserDto)

@Serializable
private data class ModulesDto(val money: Boolean = true, val wellness: Boolean = true, val ai: Boolean = true)

@Serializable
private data class SettingsDto(
    val reduceMotion: Boolean = false,
    val compact: Boolean = false,
    val modules: ModulesDto = ModulesDto(),
    val todaySections: List<String> = Settings.DEFAULT_TODAY_SECTIONS,
    val weekStart: String = "Monday",
    val dayStart: String = "06:00",
    val quietHours: Boolean = true,
    val digest: Boolean = true,
    val autoSave: String = "Small only",
    val shareWellness: Boolean = false,
    val focusMinutes: Int = 25,
)

/** The settings document is free-form JSONB on the server; anything malformed falls back to defaults. */
fun parseSettings(json: Json, obj: JsonObject): Settings = runCatching {
    val s = json.decodeFromJsonElement(SettingsDto.serializer(), obj)
    Settings(
        reduceMotion = s.reduceMotion, compact = s.compact,
        modules = Modules(s.modules.money, s.modules.wellness, s.modules.ai),
        todaySections = s.todaySections, weekStart = s.weekStart, dayStart = s.dayStart,
        quietHours = s.quietHours, digest = s.digest, autoSave = s.autoSave,
        shareWellness = s.shareWellness, focusMinutes = s.focusMinutes,
    )
}.getOrDefault(Settings())

fun UserDto.toDomain(json: Json): User = User(
    id = id, name = name, email = email, username = username, phone = phone,
    settings = parseSettings(json, settings), onboardedAt = onboardedAt, memberSince = memberSince,
)
