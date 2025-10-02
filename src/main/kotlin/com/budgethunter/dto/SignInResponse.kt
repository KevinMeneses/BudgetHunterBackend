package com.budgethunter.dto

data class SignInResponse(
    val authToken: String,
    val email: String,
    val name: String
)
