package com.budgethunter.controller

import com.budgethunter.categorization.CategorizationQuotaService
import com.budgethunter.dto.AiUsageOverviewResponse
import com.budgethunter.model.Plan
import com.budgethunter.receipts.ReceiptQuotaService
import com.budgethunter.repository.UserRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.YearMonth

/**
 * Every monthly AI allowance in one call, for the app's plan screen. Its own route rather than more fields on
 * `GET /api/receipts/usage`: that one belongs to the receipts feature (it disappears with `receipts.enabled=false`)
 * and its answer is the `usage` object of the scan responses and the 402, which must stay about scans.
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI usage", description = "The account's monthly AI allowances")
class AiUsageController(
    private val receiptQuota: ObjectProvider<ReceiptQuotaService>,
    private val categorizationQuota: ObjectProvider<CategorizationQuotaService>,
    private val userRepository: UserRepository
) {

    private val clock: Clock = Clock.systemUTC()

    @GetMapping("/usage")
    @Operation(
        summary = "This month's AI usage",
        description = "Plan, receipt scans and AI categorizations used and left, and when the counts reset " +
            "(first day of the next UTC month). The fields of a feature turned off on this server are null."
    )
    fun usage(authentication: Authentication): ResponseEntity<AiUsageOverviewResponse> {
        val email = authentication.principal as String
        val receipts = receiptQuota.ifAvailable?.usage(email)
        val categorization = categorizationQuota.ifAvailable?.usage(email)
        val month = YearMonth.now(clock)
        return ResponseEntity.ok(
            AiUsageOverviewResponse(
                plan = categorization?.plan ?: receipts?.plan ?: planOf(email).name,
                period = categorization?.period ?: receipts?.period ?: month.toString(),
                receiptScansUsed = receipts?.receiptScansUsed,
                receiptScansLimit = receipts?.receiptScansLimit,
                receiptScansRemaining = receipts?.receiptScansRemaining,
                categorizedEntriesUsed = categorization?.categorizedEntriesUsed,
                categorizedEntriesLimit = categorization?.categorizedEntriesLimit,
                categorizedEntriesRemaining = categorization?.categorizedEntriesRemaining,
                resetsOn = categorization?.resetsOn ?: receipts?.resetsOn ?: month.plusMonths(1).atDay(1).toString()
            )
        )
    }

    private fun planOf(email: String): Plan =
        userRepository.findById(email).map { it.planAt(clock.instant()) }.orElse(Plan.FREE)
}
