package com.budgethunter.exception

/**
 * Thrown when an account cannot be created because its email is already taken, including when a
 * concurrent request created it first. Mapped to HTTP 409 Conflict by [GlobalExceptionHandler].
 */
class DuplicateEmailException(message: String = "Email already exists") : RuntimeException(message)
