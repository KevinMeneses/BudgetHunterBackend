package com.budgethunter.exception

/**
 * Thrown when signing up with an email that already has an account.
 * Mapped to HTTP 409 Conflict by [GlobalExceptionHandler].
 */
class DuplicateEmailException : RuntimeException("Email already exists")
