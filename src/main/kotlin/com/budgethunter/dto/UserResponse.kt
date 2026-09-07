package com.budgethunter.dto

import com.budgethunter.model.AuthProvider

data class UserResponse(
    val email: String,
    val name: String,
    /**
     * Whether the account can also be signed into with a password. The settings screen uses it to
     * decide between offering to set a first password and offering to change an existing one.
     */
    val hasPassword: Boolean = false,
    val authProvider: AuthProvider = AuthProvider.PASSWORD
)
