package com.budgethunter.dto

import com.budgethunter.privacy.PRIVACY_POLICY_VERSION
import jakarta.validation.constraints.NotBlank
import java.time.Instant

/** Body of `POST /api/users/me/privacy-policy`: the version the user just accepted in the app. */
data class AcceptPrivacyPolicyRequest(
    @field:NotBlank(message = "Version is required")
    val version: String
)

/**
 * Whether the account accepted the privacy policy in force. The app asks for the acceptance when
 * [acceptedVersion] differs from [currentVersion] (null = never accepted, as every account created before
 * acceptances were recorded). Every field has a default, so clients may ignore the object.
 */
data class PrivacyPolicyStatus(
    val currentVersion: String = PRIVACY_POLICY_VERSION,
    val acceptedVersion: String? = null,
    /** When the server recorded the acceptance of [acceptedVersion] (its own clock, not the device's). */
    val acceptedAt: Instant? = null
)
