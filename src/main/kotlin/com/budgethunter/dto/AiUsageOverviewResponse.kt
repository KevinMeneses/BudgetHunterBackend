package com.budgethunter.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Every monthly AI allowance of the account in one answer, for the app's plan screen. The receipt fields have the
 * same names as in [AiUsageResponse], so a client can read either. A feature turned off on this server
 * (`receipts.enabled` / `categorization.enabled`) has its three fields null.
 */
@Schema(description = "All of the account's monthly AI allowances (UTC calendar month)")
data class AiUsageOverviewResponse(
    @field:Schema(description = "The account's plan", example = "FREE", allowableValues = ["FREE", "PREMIUM"])
    val plan: String,
    @field:Schema(description = "The month counted, YYYY-MM (UTC)", example = "2026-11")
    val period: String,
    @field:Schema(description = "Receipt scans used this month; null if receipts are off", example = "3")
    val receiptScansUsed: Int?,
    @field:Schema(description = "Receipt scans the plan includes per month", example = "10")
    val receiptScansLimit: Int?,
    @field:Schema(description = "Receipt scans left this month", example = "7")
    val receiptScansRemaining: Int?,
    @field:Schema(
        description = "Descriptions sent to the AI for automatic categorization this month; null if it is off",
        example = "12"
    )
    val categorizedEntriesUsed: Int?,
    @field:Schema(description = "Descriptions the plan may send to the AI per month", example = "50")
    val categorizedEntriesLimit: Int?,
    @field:Schema(description = "Descriptions left this month", example = "38")
    val categorizedEntriesRemaining: Int?,
    @field:Schema(description = "First day (UTC) of the next month, when the counts start over", example = "2026-12-01")
    val resetsOn: String
)
