package com.budgethunter.dto

import java.math.BigDecimal
import java.time.LocalDate

data class BudgetResponse(
    val id: Long,
    val name: String,
    val amount: BigDecimal,
    val date: LocalDate? = null,
    /** Number of users with access to the budget, the owner and the requesting user included. */
    val collaboratorCount: Int
)
