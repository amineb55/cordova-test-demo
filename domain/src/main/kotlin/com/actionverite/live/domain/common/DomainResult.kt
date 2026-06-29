package com.actionverite.live.domain.common

/**
 * A lightweight, framework-agnostic result type used across the domain and
 * data layers. Prefer this over throwing for *expected* failures (network,
 * validation, permission) so call sites are forced to handle both branches.
 */
sealed interface DomainResult<out T> {
    data class Success<out T>(val data: T) : DomainResult<T>
    data class Failure(val error: DomainError) : DomainResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.data

    fun errorOrNull(): DomainError? = (this as? Failure)?.error
}

inline fun <T, R> DomainResult<T>.map(transform: (T) -> R): DomainResult<R> =
    when (this) {
        is DomainResult.Success -> DomainResult.Success(transform(data))
        is DomainResult.Failure -> this
    }

inline fun <T> DomainResult<T>.onSuccess(action: (T) -> Unit): DomainResult<T> {
    if (this is DomainResult.Success) action(data)
    return this
}

inline fun <T> DomainResult<T>.onFailure(action: (DomainError) -> Unit): DomainResult<T> {
    if (this is DomainResult.Failure) action(error)
    return this
}

fun <T> T.asSuccess(): DomainResult<T> = DomainResult.Success(this)
fun DomainError.asFailure(): DomainResult<Nothing> = DomainResult.Failure(this)

/** Exhaustive, typed catalogue of failures the domain can surface to the UI. */
sealed class DomainError(val message: String? = null) {
    data object Network : DomainError("No network connection.")
    data object Timeout : DomainError("The operation timed out.")
    data object Unauthenticated : DomainError("You must be signed in.")
    data object PermissionDenied : DomainError("You don't have permission to do that.")
    data class NotFound(val what: String) : DomainError("$what not found.")
    data class Validation(val reason: String) : DomainError(reason)
    data class RoomFull(val capacity: Int) : DomainError("Room is full ($capacity players).")
    data object Underage : DomainError("Content not allowed for this account's age.")
    data class Moderation(val reason: String) : DomainError(reason)
    data class Unknown(val cause: String? = null) : DomainError(cause ?: "Something went wrong.")
}
