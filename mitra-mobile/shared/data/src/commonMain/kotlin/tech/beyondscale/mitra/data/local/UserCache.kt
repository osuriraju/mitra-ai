package tech.beyondscale.mitra.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.remote.UserDto
import tech.beyondscale.mitra.data.remote.toDomain
import tech.beyondscale.mitra.domain.model.User

class UserCache(private val db: MitraDb, private val json: Json, private val io: CoroutineDispatcher) {
    fun observe(): Flow<User?> = db.userQueries.selectCurrent().asFlow().mapToOneOrNull(io)
        .map { raw -> raw?.let { json.decodeFromString<UserDto>(it).toDomain(json) } }

    fun current(): UserDto? = db.userQueries.selectCurrent().executeAsOneOrNull()?.let { json.decodeFromString<UserDto>(it) }

    fun put(dto: UserDto) = db.userQueries.upsert(dto.id, json.encodeToString(UserDto.serializer(), dto))
}
