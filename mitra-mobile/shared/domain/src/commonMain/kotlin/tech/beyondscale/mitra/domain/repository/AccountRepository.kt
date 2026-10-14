package tech.beyondscale.mitra.domain.repository

import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.User

/** Online-only account operations on `users/me` (web Profile and Onboarding screens). */
interface AccountRepository {
    /** PATCH users/me — name, email and phone (blank phone clears it). */
    suspend fun updateProfile(name: String, email: String, phone: String?): Outcome<User>
    /** PATCH users/me/password — the server signs every other device out. */
    suspend fun changePassword(current: String, new: String): Outcome<Unit>
    /** GET users/me/export — the account's data as pretty-printed JSON. */
    suspend fun exportData(): Outcome<String>
    /** DELETE users/me (30-day grace on the server), then signs out and wipes this device. */
    suspend fun deleteAccount(password: String): Outcome<Unit>
    /** POST users/me/onboarded with the modules and digest picked during onboarding. */
    suspend fun markOnboarded(modules: Modules, digest: Boolean): Outcome<User>
}
