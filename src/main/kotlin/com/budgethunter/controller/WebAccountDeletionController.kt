package com.budgethunter.controller

import com.budgethunter.deletion.WebAccountDeletionService
import com.budgethunter.dto.DeleteWithCredentialsRequest
import com.budgethunter.dto.DeleteWithGoogleRequest
import com.budgethunter.dto.WebAccountDeletionConfigResponse
import com.budgethunter.dto.WebAccountDeletionErrorResponse
import com.budgethunter.dto.WebAccountDeletionResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The endpoints behind the public page `/eliminar-cuenta` (Google Play requires a way to delete the account
 * without the app). Public in SecurityConfig: each request carries its own proof of identity.
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "User Management")
@SecurityRequirements
class WebAccountDeletionController(
    private val webAccountDeletionService: WebAccountDeletionService,
    /** Optional override; otherwise the first of `google.auth.client-ids`, which is the web one. */
    @Value("\${google.auth.web-client-id:}") private val webClientId: String,
    @Value("\${google.auth.client-ids:}") private val clientIds: List<String>
) {

    @PostMapping("/delete_with_credentials")
    @Operation(
        summary = "Delete an account with its email and password (public deletion page)",
        description = "Checks the credentials like sign_in and deletes the account like DELETE /api/users/me. " +
            "Every failure (unknown email, wrong password, account without a password) is the same 401 " +
            "INVALID_CREDENTIALS. Limited per IP and per email (429 TOO_MANY_ATTEMPTS)."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Account deleted. cancelSubscriptionInStore is true for a PREMIUM account",
                content = [Content(schema = Schema(implementation = WebAccountDeletionResponse::class))]
            ),
            ApiResponse(responseCode = "400", description = "Missing field, or confirm is not true", content = [Content()]),
            ApiResponse(
                responseCode = "401",
                description = "INVALID_CREDENTIALS",
                content = [Content(schema = Schema(implementation = WebAccountDeletionErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "429",
                description = "TOO_MANY_ATTEMPTS",
                content = [Content(schema = Schema(implementation = WebAccountDeletionErrorResponse::class))]
            )
        ]
    )
    fun deleteWithCredentials(
        @Valid @RequestBody request: DeleteWithCredentialsRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<WebAccountDeletionResponse> = ResponseEntity.ok(
        webAccountDeletionService.deleteWithCredentials(request.email, request.password, clientIp(httpRequest))
    )

    @PostMapping("/delete_with_google")
    @Operation(
        summary = "Delete an account with a Google ID token (public deletion page)",
        description = "Verifies the token like sign_in_with_google and deletes the account it opens. " +
            "401 INVALID_GOOGLE_TOKEN, 404 NO_ACCOUNT, 429 TOO_MANY_ATTEMPTS (per IP)."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Account deleted",
                content = [Content(schema = Schema(implementation = WebAccountDeletionResponse::class))]
            ),
            ApiResponse(responseCode = "400", description = "Missing token, or confirm is not true", content = [Content()]),
            ApiResponse(
                responseCode = "401",
                description = "INVALID_GOOGLE_TOKEN",
                content = [Content(schema = Schema(implementation = WebAccountDeletionErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "NO_ACCOUNT",
                content = [Content(schema = Schema(implementation = WebAccountDeletionErrorResponse::class))]
            ),
            ApiResponse(
                responseCode = "429",
                description = "TOO_MANY_ATTEMPTS",
                content = [Content(schema = Schema(implementation = WebAccountDeletionErrorResponse::class))]
            )
        ]
    )
    fun deleteWithGoogle(
        @Valid @RequestBody request: DeleteWithGoogleRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<WebAccountDeletionResponse> = ResponseEntity.ok(
        webAccountDeletionService.deleteWithGoogle(request.idToken, clientIp(httpRequest))
    )

    @GetMapping("/delete_account/config")
    @Operation(
        summary = "What the public deletion page needs",
        description = "The Google web client id for the Sign in with Google button; null when Google sign in " +
            "is not configured, and the page then hides the button."
    )
    fun config(): ResponseEntity<WebAccountDeletionConfigResponse> = ResponseEntity.ok(
        WebAccountDeletionConfigResponse(
            webClientId.trim().ifEmpty { null }
                ?: clientIds.map(String::trim).firstOrNull(String::isNotEmpty)
        )
    )

    /**
     * Nginx overwrites X-Real-IP with the address that connected to it, so a client cannot choose it (unlike the
     * first entry of X-Forwarded-For, which Nginx appends to). Without Nginx, the connecting address.
     */
    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Real-IP")?.trim()?.takeIf { it.isNotEmpty() } ?: request.remoteAddr ?: "unknown"
}
