package com.budgethunter.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "How much of the paid AI the account used this month (UTC calendar month)")
data class AiUsageResponse(
    @field:Schema(description = "The account's plan", example = "FREE", allowableValues = ["FREE", "PREMIUM"])
    val plan: String,
    @field:Schema(description = "The month counted, YYYY-MM (UTC)", example = "2026-11")
    val period: String,
    @field:Schema(description = "Receipt scans used this month", example = "3")
    val receiptScansUsed: Int,
    @field:Schema(description = "Receipt scans the plan includes per month", example = "10")
    val receiptScansLimit: Int,
    @field:Schema(description = "Receipt scans left this month", example = "7")
    val receiptScansRemaining: Int,
    @field:Schema(description = "First day (UTC) of the next month, when the count starts over", example = "2026-12-01")
    val resetsOn: String
)
