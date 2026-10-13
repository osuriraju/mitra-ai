package tech.beyondscale.mitra.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import tech.beyondscale.mitra.data.db.MitraDb

actual fun testDriver(): SqlDriver = inMemoryDriver(MitraDb.Schema)
