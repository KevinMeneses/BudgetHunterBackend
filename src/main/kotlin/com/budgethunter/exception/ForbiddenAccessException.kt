package com.budgethunter.exception

/**
 * Exception thrown when an authenticated user attempts to access a resource they don't have permission to access.
 * This will be mapped to HTTP 403 Forbidden.
 */
class ForbiddenAccessException(message: String) : RuntimeException(message)