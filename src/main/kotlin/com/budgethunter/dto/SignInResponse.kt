package com.budgethunter.dto

data class SignInResponse(
    val authToken: String,
    val refreshToken: String,
    val email: String,
    val name: String,
    // True only on a Google sign-in that linked an account and removed the password it had (UserService.linkGoogle),
    // so the app can say so. Always present; older app builds ignore it.
    val passwordRemoved: Boolean = false
)
