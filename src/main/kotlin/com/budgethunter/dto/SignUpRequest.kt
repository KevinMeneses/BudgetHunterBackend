package com.budgethunter.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class SignUpRequest(
    @field:Email(message = "Email must be valid")
    @field:NotBlank(message = "Email is required")
    val email: String,

    @field:NotBlank(message = "Name is required")
    val name: String,

    @field:NotBlank(message = "Password is required")
    @field:Size(min = 6, message = "Password must be at least 6 characters")
    val password: String,

    /**
     * The privacy policy version the user accepted on the sign-up screen. Optional, so app builds that predate it
     * keep working; recorded only when it is the current version, and ignored otherwise.
     */
    val acceptedPrivacyPolicyVersion: String? = null
)
