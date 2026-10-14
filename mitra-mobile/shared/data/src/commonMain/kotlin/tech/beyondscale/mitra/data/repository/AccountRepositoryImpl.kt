package tech.beyondscale.mitra.data.repository

import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.map
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.UserCache
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.UserEnvelope
import tech.beyondscale.mitra.data.remote.toDomain
import tech.beyondscale.mitra.data.sync.withPendingSettings
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.User
import tech.beyondscale.mitra.domain.repository.AccountRepository
import tech.beyondscale.mitra.domain.repository.AuthRepository

class AccountRepositoryImpl(
    private val api: ApiClient,
    private val db: MitraDb,
    private val cache: UserCache,
    private val prefs: PrefStore,
    private val auth: AuthRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AccountRepository {
    private val pretty = Json { prettyPrint = true }

    override suspend fun updateProfile(name: String, email: String, phone: String?): Outcome<User> = adopt(
        api.call<UserEnvelope>(HttpMethod.Patch, "users/me", buildJsonObject {
            put("name", name.trim()); put("email", email.trim())
            put("phone", phone?.trim()?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
        }),
    )

    override suspend fun changePassword(current: String, new: String): Outcome<Unit> =
        api.send(HttpMethod.Patch, "users/me/password", buildJsonObject { put("currentPassword", current); put("newPassword", new) }).map { }

    override suspend fun exportData(): Outcome<String> =
        api.send(HttpMethod.Get, "users/me/export").map { pretty.encodeToString(JsonElement.serializer(), it ?: JsonNull) }

    override suspend fun deleteAccount(password: String): Outcome<Unit> {
        val r = api.send(HttpMethod.Delete, "users/me", buildJsonObject { put("password", password) })
        if (r is Outcome.Err) return r
        auth.logout() // the server already ended every session: this clears the cookie and wipes the device
        return Outcome.Ok(Unit)
    }

    override suspend fun markOnboarded(modules: Modules, digest: Boolean): Outcome<User> = adopt(
        api.call<UserEnvelope>(HttpMethod.Post, "users/me/onboarded", buildJsonObject {
            putJsonObject("settings") {
                putJsonObject("modules") { put("money", modules.money); put("wellness", modules.wellness); put("ai", modules.ai) }
                put("digest", digest)
            }
        }),
    )

    /** Caches the returned user (queued settings stay on top) unless the account changed meanwhile. */
    private suspend fun adopt(r: Outcome<UserEnvelope>): Outcome<User> = when (r) {
        is Outcome.Err -> r
        is Outcome.Ok -> withContext(io) {
            val dto = db.transactionWithResult {
                r.value.user.withPendingSettings(db, api.json).also { if (prefs.get(PrefKeys.LAST_USER_ID) == it.id) cache.put(it) }
            }
            Outcome.Ok(dto.toDomain(api.json))
        }
    }
}
