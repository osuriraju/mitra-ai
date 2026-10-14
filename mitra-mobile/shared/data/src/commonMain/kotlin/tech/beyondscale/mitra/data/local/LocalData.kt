package tech.beyondscale.mitra.data.local

import tech.beyondscale.mitra.data.db.MitraDb

class LocalData(private val db: MitraDb) {
    /** Removes everything that belongs to the signed-in account. Each later module adds its tables here. */
    fun clearUserData() = db.transaction {
        db.outboxQueries.deleteAll()
        db.userQueries.deleteAll()
        db.tasksQueries.deleteAll()
        db.projectsQueries.deleteAll()
        db.accountsQueries.deleteAll()
        db.categoriesQueries.deleteAll()
        db.transactionsQueries.deleteAll()
        db.recurringQueries.deleteAll()
        db.subscriptionsQueries.deleteAll()
    }
}
