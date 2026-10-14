package tech.beyondscale.mitra.domain.repository

/** Keeps each module's local data fresh (spec §6 read triggers); refreshes are throttled per module. */
interface ModuleRefresher {
    /** The app came to the foreground: refresh every module whose last refresh is at least a minute old. */
    fun onForeground()
}
