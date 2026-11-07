package com.budgethunter.controller

import com.budgethunter.billing.BillingService
import com.budgethunter.dto.BillingStatusResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
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
