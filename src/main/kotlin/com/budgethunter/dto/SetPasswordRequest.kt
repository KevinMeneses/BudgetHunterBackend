package com.budgethunter.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class SetPasswordRequest(
    /**
     * Null when the account has no password yet, which is the case for one created through Google.
     * Asking such a user for a password they never chose would be a dead end; the bearer token on
     * the request already proves who they are.
     */
    val currentPassword: String? = null,

    @field:NotBlank(message = "Password is required")
    @field:Size(min = 6, message = "Password must be at least 6 characters")
    val newPassword: String
)
