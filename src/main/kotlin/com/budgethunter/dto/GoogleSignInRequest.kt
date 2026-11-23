package com.budgethunter.dto

import jakarta.validation.constraints.NotBlank

data class GoogleSignInRequest(
    @field:NotBlank(message = "Google ID token is required")
    val idToken: String,

    /**
     * The privacy policy version the user accepted on the sign-in screen. Optional, so app builds that predate
     * it keep working; recorded only when it is the current version and the account has not accepted it yet.
     */
    val acceptedPrivacyPolicyVersion: String? = null
)
