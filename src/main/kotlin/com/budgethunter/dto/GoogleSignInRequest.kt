package com.budgethunter.dto

import jakarta.validation.constraints.NotBlank

data class GoogleSignInRequest(
    @field:NotBlank(message = "Google ID token is required")
    val idToken: String
)
