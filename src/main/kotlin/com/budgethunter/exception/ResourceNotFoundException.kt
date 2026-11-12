package com.budgethunter.exception

/**
 * Thrown when the resource a request names does not exist: a budget, an entry, an entry that is not in the
 * budget of the path, or a collaborator that is not on the budget. Mapped to HTTP 404 Not Found by
 * [GlobalExceptionHandler].
 *
 * Not for the authenticated account itself: a valid token whose account is gone is a 401
 * (`BadCredentialsException`), so the client signs in again instead of treating the resource as gone.
 */
class ResourceNotFoundException(message: String) : RuntimeException(message)
