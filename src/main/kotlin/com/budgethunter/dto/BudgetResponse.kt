package com.budgethunter.dto

import java.math.BigDecimal

data class BudgetResponse(
    val id: Long,
    val name: String,
    val amount: BigDecimal
)
