package com.budgethunter.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal

data class CreateBudgetRequest(
    @field:NotBlank(message = "Budget name is required")
    val name: String,

    @field:PositiveOrZero(message = "Budget amount must be zero or positive")
    val amount: BigDecimal
)
