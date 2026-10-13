package tech.beyondscale.mitra.app

import app.cash.sqldelight.db.SqlDriver

/** A fresh in-memory database with the schema created. */
expect fun testSqlDriver(): SqlDriver
