package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

class UserDtoTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Test fun mapsServerUserAndMergesSettingsOverDefaults() {
        val env = json.decodeFromString<UserEnvelope>(userJson(settings = """{"modules":{"money":false,"wellness":true,"ai":true},"focusMinutes":50,"unknownKey":1}"""))
        val user = env.user.toDomain(json)
        assertEquals("Raju", user.firstName)
        assertEquals("RO", user.initials)
        assertEquals(Modules(money = false), user.settings.modules)
        assertEquals(50, user.settings.focusMinutes)
        assertEquals(Settings.DEFAULT_TODAY_SECTIONS, user.settings.todaySections)
    }

    @Test fun wrongTypedSettingsFallBackToDefaults() {
        val obj = json.parseToJsonElement("""{"modules":"yes","focusMinutes":"lots"}""").jsonObject
        assertEquals(Settings(), parseSettings(json, obj))
    }
}
