package com.budgethunter.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(
    description = "What the account can share as an owner. A null limit means unlimited (PREMIUM). Only budgets " +
        "the account owns count; being invited to someone else's budget is free."
)
data class SharingStatusResponse(
    @field:Schema(description = "FREE or PREMIUM, as in force right now", example = "FREE")
    val plan: String = "FREE",
    @field:Schema(description = "How many budgets the account may share; null = unlimited", example = "1")
    val sharedBudgetsLimit: Int? = null,
    @field:Schema(
        description = "Budgets the account owns that have at least one member besides it. Can be above the " +
            "limit (shared before the limit existed, while PREMIUM, or handed over): nothing is taken away.",
        example = "0"
    )
    val sharedBudgetsUsed: Int = 0,
    @field:Schema(
        description = "Members besides the owner each budget the account owns may have; null = unlimited",
        example = "1"
    )
    val collaboratorsPerBudgetLimit: Int? = null
)

@Schema(description = "Which sharing limit an addition hit, for the app to explain it and offer PREMIUM")
data class SharingLimitDetail(
    @field:Schema(description = "The limit that was hit", allowableValues = ["SHARED_BUDGETS", "COLLABORATORS_PER_BUDGET"])
    val limitType: String,
    @field:Schema(description = "The plan of the budget's owner (whose limit applies), not of the caller", example = "FREE")
    val plan: String,
    @field:Schema(description = "That limit on the owner's plan", example = "1")
    val limit: Int,
    @field:Schema(
        description = "Shared budgets the owner has, or members besides the owner in this budget",
        example = "1"
    )
    val used: Int,
    @field:Schema(description = "Whether the caller is the owner (only the owner's upgrade lifts the limit)")
    val callerIsOwner: Boolean
)

@Schema(description = "An addition refused by the sharing limits of the budget owner's plan (HTTP 402)")
data class SharingErrorResponse(
    val status: Int,
    @field:Schema(description = "Machine-readable reason", allowableValues = ["SHARING_LIMIT_REACHED"])
    val code: String,
    val message: String,
    val sharing: SharingLimitDetail
)
