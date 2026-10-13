package tech.beyondscale.mitra.data

import app.cash.sqldelight.db.SqlDriver

/** A fresh in-memory database with the schema created. */
expect fun testDriver(): SqlDriver
