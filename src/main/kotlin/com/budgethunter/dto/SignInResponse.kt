package com.budgethunter.dto

data class SignInResponse(
    val authToken: String,
    val refreshToken: String,
    val email: String,
    val name: String
)
