package com.budgethunter.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class CollaboratorResponse(
    val budgetId: Long,
    val budgetName: String,
    val collaboratorEmail: String,
    val collaboratorName: String,
    /** Whether this member owns the budget. Added later: older clients ignore it. */
    @get:JsonProperty("isOwner")
    val isOwner: Boolean = false
)
