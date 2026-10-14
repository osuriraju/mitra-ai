package tech.beyondscale.mitra.testing

import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.User
import tech.beyondscale.mitra.domain.repository.AccountRepository
import tech.beyondscale.mitra.domain.repository.SettingChange
import tech.beyondscale.mitra.domain.repository.SettingsEditor

class FakeAccountRepository : AccountRepository {
    val calls = mutableListOf<String>()
    var userResult: Outcome<User> = Outcome.Ok(testUser())
    var unitResult: Outcome<Unit> = Outcome.Ok(Unit)
    var exportResult: Outcome<String> = Outcome.Ok("{}")
    override suspend fun updateProfile(name: String, email: String, phone: String?): Outcome<User> { calls += "profile:$name:$email:$phone"; return userResult }
    override suspend fun changePassword(current: String, new: String): Outcome<Unit> { calls += "password"; return unitResult }
    override suspend fun exportData(): Outcome<String> { calls += "export"; return exportResult }
    override suspend fun deleteAccount(password: String): Outcome<Unit> { calls += "delete:$password"; return unitResult }
    override suspend fun markOnboarded(modules: Modules, digest: Boolean): Outcome<User> { calls += "onboarded:$modules:$digest"; return userResult }
}

class FakeSettingsEditor : SettingsEditor {
    val changes = mutableListOf<SettingChange>()
    override suspend fun update(change: SettingChange) { changes += change }
}
