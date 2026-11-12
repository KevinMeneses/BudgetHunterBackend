package com.budgethunter.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * One entry of a budget's member list (`GET /api/budgets/{id}/collaborators`). The same `email` and `name`
 * as [UserResponse], which is what the endpoint returned before, plus whether this member owns the budget,
 * so the app can offer "Delete" to the owner and "Leave" to everyone else. Older clients ignore the flag.
 */
data class BudgetMemberResponse(
    val email: String,
    val name: String,
    @get:JsonProperty("isOwner")
    val isOwner: Boolean
)
