package tech.beyondscale.mitra.core

/** Result of an operation that can fail with a typed [AppError]. */
sealed interface Outcome<out T> {
    data class Ok<out T>(val value: T) : Outcome<T>
    data class Err(val error: AppError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}

inline fun <T> Outcome<T>.onOk(action: (T) -> Unit): Outcome<T> { if (this is Outcome.Ok) action(value); return this }
inline fun <T> Outcome<T>.onErr(action: (AppError) -> Unit): Outcome<T> { if (this is Outcome.Err) action(error); return this }
