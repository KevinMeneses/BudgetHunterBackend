package com.budgethunter.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "How much automatic categorization with AI the account used this month (UTC calendar month)")
data class CategorizationUsageResponse(
    @field:Schema(description = "The account's plan", example = "FREE", allowableValues = ["FREE", "PREMIUM"])
    val plan: String,
    @field:Schema(description = "The month counted, YYYY-MM (UTC)", example = "2026-11")
    val period: String,
    @field:Schema(
        description = "Distinct descriptions sent to the AI this month. What the keyword rules or the cache answer " +
            "is free and not counted",
        example = "12"
    )
    val categorizedEntriesUsed: Int,
    @field:Schema(description = "Descriptions the plan may send to the AI per month", example = "50")
    val categorizedEntriesLimit: Int,
    @field:Schema(description = "Descriptions left this month", example = "38")
    val categorizedEntriesRemaining: Int,
    @field:Schema(description = "First day (UTC) of the next month, when the count starts over", example = "2026-12-01")
    val resetsOn: String
)
