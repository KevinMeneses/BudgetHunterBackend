package com.budgethunter.dto

/**
 * A user as seen by *other* users - the collaborators list, for instance.
 *
 * Deliberately carries nothing about how the account authenticates; that belongs to
 * [CurrentUserResponse], which only ever describes the caller themselves.
 */
data class UserResponse(
    val email: String,
    val name: String,
)
