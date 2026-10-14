package tech.beyondscale.mitra.core

/**
 * Minimal logger for failures that are deliberately swallowed (a sync handler, a background refresh) so they are
 * visible in logcat instead of vanishing. The platform installs [sink] at startup (Android: `Log.w`); the default
 * prints, which also shows up in host tests.
 */
object MitraLog {
    var sink: (tag: String, message: String, error: Throwable?) -> Unit = { tag, message, error ->
        println("W/$tag: $message${error?.let { " — $it" } ?: ""}")
    }

    fun warn(tag: String, message: String, error: Throwable? = null) {
        try { sink(tag, message, error) } catch (_: Throwable) { }
    }
}
