package com.budgethunter.dto

import com.budgethunter.model.AuthProvider

/**
 * The authenticated user's own profile.
 *
 * Separate from [UserResponse] because [hasPassword] and [authProvider] describe how an account
 * signs in, which is nobody else's business - and [UserResponse] is what the collaborators list
 * hands to every member of a shared budget.
 */
data class CurrentUserResponse(
    val email: String,
    val name: String,
    /**
     * Whether the account can also be signed into with a password. The settings screen uses it to
     * decide between offering to set a first password and offering to change an existing one.
     */
    val hasPassword: Boolean,
    val authProvider: AuthProvider,
)
