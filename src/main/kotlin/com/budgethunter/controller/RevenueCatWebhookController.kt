package com.budgethunter.controller

import com.budgethunter.billing.BillingService
import com.budgethunter.billing.WebhookAuthorization
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** RevenueCat's webhook. Public (no JWT): it is authenticated by the shared secret in `Authorization`. */
@RestController
@RequestMapping("/api/webhooks")
@Tag(name = "Billing")
class RevenueCatWebhookController(
    private val billingService: BillingService,
    private val webhookAuthorization: WebhookAuthorization,
    private val objectMapper: ObjectMapper
) {

    @PostMapping("/revenuecat")
    @Operation(
        summary = "RevenueCat webhook",
        description = "Refreshes the plan of every account the event names, from RevenueCat's API. 401 without the " +
            "shared secret; 503 when RevenueCat cannot be asked (RevenueCat then retries)."
    )
    fun revenueCat(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody body: String
    ): ResponseEntity<Map<String, Int>> {
        // The body is read as text and parsed only once the secret matches, so strangers get nothing parsed.
        if (!webhookAuthorization.matches(authorization)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val event = parsed(body)
        return if (event == null) {
            ResponseEntity.badRequest().build()
        } else {
            ResponseEntity.ok(mapOf("accountsRefreshed" to billingService.onWebhook(event)))
        }
    }

    private fun parsed(body: String): JsonNode? = try {
        objectMapper.readTree(body)
    } catch (_: JsonProcessingException) {
        null
    }
}
