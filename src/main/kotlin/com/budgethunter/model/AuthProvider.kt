package com.budgethunter.model

/**
 * How a user is able to authenticate.
 *
 * A [PASSWORD] account can gain Google access by signing in with a Google account that carries the
 * same verified email, and a [GOOGLE] account can gain a password from `POST /api/users/password`.
 * Either path lands on [PASSWORD_AND_GOOGLE].
 */
enum class AuthProvider {
    PASSWORD,
    GOOGLE,
    PASSWORD_AND_GOOGLE,
}
