package com.budgethunter.controller

import com.budgethunter.billing.BillingService
import com.budgethunter.billing.WebhookAuthorization
import com.budgethunter.dto.BillingStatusResponse
import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/billing")
@Tag(name = "Billing", description = "The account's plan and its link to the store purchases (RevenueCat)")
class BillingController(private val billingService: BillingService) {

    @GetMapping("/status")
    @Operation(
        summary = "The account's plan and RevenueCat id",
        description = "The app logs in to RevenueCat with appUserId after sign-in, so purchases land on this account."
    )
    fun status(authentication: Authentication): ResponseEntity<BillingStatusResponse> =
        ResponseEntity.ok(billingService.status(authentication.principal as String))

    @PostMapping("/sync")
    @Operation(
        summary = "Re-read the plan from RevenueCat now",
        description = "Called by the app after a purchase or a restore, so premium applies without waiting for " +
            "the webhook. 503 when RevenueCat cannot be reached."
    )
    fun sync(authentication: Authentication): ResponseEntity<BillingStatusResponse> =
        ResponseEntity.ok(billingService.sync(authentication.principal as String))
}

/** RevenueCat's webhook. Public (no JWT): it is authenticated by the shared secret in `Authorization`. */
@RestController
@RequestMapping("/api/webhooks")
@Tag(name = "Billing")
class RevenueCatWebhookController(
    private val billingService: BillingService,
    private val webhookAuthorization: WebhookAuthorization
) {

    @PostMapping("/revenuecat")
    @Operation(
        summary = "RevenueCat webhook",
        description = "Refreshes the plan of every account the event names, from RevenueCat's API. 401 without the " +
            "shared secret; 503 when RevenueCat cannot be asked (RevenueCat then retries)."
    )
    fun revenueCat(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody body: JsonNode
    ): ResponseEntity<Map<String, Int>> {
        if (!webhookAuthorization.matches(authorization)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return ResponseEntity.ok(mapOf("accountsRefreshed" to billingService.onWebhook(body)))
    }
}
