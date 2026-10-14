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
        db.habitsQueries.deleteAll()
        db.habitLogsQueries.deleteAll()
        db.goalsQueries.deleteAll()
        db.milestonesQueries.deleteAll()
        db.goalProgressQueries.deleteAll()
        db.wellnessEntriesQueries.deleteAll()
        db.wellnessSettingsQueries.deleteAll()
        db.notesQueries.deleteAll()
        db.activityQueries.deleteAll()
        db.aiActionsQueries.deleteAll()
        db.aiMessagesQueries.deleteAll()
        db.suggestionsQueries.deleteAll()
        db.prefQueries.remove(PrefKeys.MONEY_REJECTED_TX)
    }
}
