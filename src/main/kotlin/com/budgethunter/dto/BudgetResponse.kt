package com.budgethunter.dto

import java.math.BigDecimal
import java.time.LocalDate

data class BudgetResponse(
    val id: Long,
    val name: String,
    val amount: BigDecimal,
    val date: LocalDate? = null,
    /** Number of users with access to the budget, the owner and the requesting user included. */
    val collaboratorCount: Int,
    /**
     * Email of the member who owns the budget: the only one who can delete it for everyone or remove other
     * members; everyone else can only leave. Nullable only so older clients and fixtures need not know it;
     * the server always fills it.
     */
    val ownerEmail: String? = null
)
