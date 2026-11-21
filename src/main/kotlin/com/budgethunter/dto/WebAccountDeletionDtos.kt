package com.budgethunter.dto

import com.budgethunter.model.Plan
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank

/**
 * `POST /api/users/delete_with_credentials`, sent by the public page `/eliminar-cuenta`: there is no session in
 * the browser, so the credentials travel in this one request. [confirm] is the page's mandatory checkbox, checked
 * again here so nothing but an explicit "yes" deletes an account.
 */
data class DeleteWithCredentialsRequest(
    // No @Email: a malformed address gets the same generic 401 as a wrong password.
    @field:NotBlank(message = "Email is required")
    val email: String,

    @field:NotBlank(message = "Password is required")
    val password: String,

    @field:AssertTrue(message = "Deletion must be confirmed")
    val confirm: Boolean = false
)

/** `POST /api/users/delete_with_google`: a Google ID token from Google Identity Services on the page. */
data class DeleteWithGoogleRequest(
    @field:NotBlank(message = "Google ID token is required")
    val idToken: String,

    @field:AssertTrue(message = "Deletion must be confirmed")
    val confirm: Boolean = false
)

/**
 * What the page shows after deleting. [plan] is the account's plan at the moment it was deleted;
 * [cancelSubscriptionInStore] is true when it was PREMIUM: deleting the account does not cancel the store
 * subscription, so the page tells the user to cancel it in Google Play.
 */
data class WebAccountDeletionResponse(
    val deleted: Boolean,
    val plan: Plan,
    val cancelSubscriptionInStore: Boolean
)

/** `GET /api/users/delete_account/config`: what the page needs to show the Google button; null hides it. */
data class WebAccountDeletionConfigResponse(
    val googleClientId: String?
)

/** Errors of the web deletion, with a stable [code] the page maps to its own Spanish messages. */
data class WebAccountDeletionErrorResponse(
    val status: Int,
    val code: String,
    val message: String
)
