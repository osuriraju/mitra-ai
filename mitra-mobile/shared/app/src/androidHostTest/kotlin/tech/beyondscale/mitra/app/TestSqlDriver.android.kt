package tech.beyondscale.mitra.app

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tech.beyondscale.mitra.data.db.MitraDb

actual fun testSqlDriver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MitraDb.Schema.create(it) }
