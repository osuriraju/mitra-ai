package tech.beyondscale.mitra.app

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import tech.beyondscale.mitra.data.db.MitraDb

actual fun testSqlDriver(): SqlDriver = inMemoryDriver(MitraDb.Schema)
