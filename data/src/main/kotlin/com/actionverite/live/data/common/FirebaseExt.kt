package com.actionverite.live.data.common

import com.actionverite.live.domain.common.DomainError
import com.actionverite.live.domain.common.DomainResult
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException

/**
 * Runs a suspending Firebase call and maps both success and the common Firebase
 * exceptions onto the domain's [DomainResult]/[DomainError] vocabulary, so the
 * rest of the app never sees a raw Firebase type.
 *
 * [CancellationException] is rethrown so structured concurrency keeps working.
 */
suspend inline fun <T> firebaseCall(crossinline block: suspend () -> T): DomainResult<T> =
    try {
        DomainResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        DomainResult.Failure(e.toDomainError())
    }

fun Throwable.toDomainError(): DomainError = when (this) {
    is FirebaseNetworkException -> DomainError.Network
    is FirebaseAuthException -> DomainError.Unauthenticated
    is FirebaseFirestoreException -> when (code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> DomainError.PermissionDenied
        FirebaseFirestoreException.Code.NOT_FOUND -> DomainError.NotFound("document")
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> DomainError.Network
        else -> DomainError.Unknown(message)
    }
    is FirebaseFunctionsException -> when (code) {
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> DomainError.Unauthenticated
        FirebaseFunctionsException.Code.PERMISSION_DENIED -> DomainError.PermissionDenied
        FirebaseFunctionsException.Code.UNAVAILABLE,
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> DomainError.Network
        else -> DomainError.Unknown(message)
    }
    else -> DomainError.Unknown(message)
}
