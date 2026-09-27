package com.budgethunter.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal
import java.time.LocalDate

data class UpdateBudgetRequest(
    @field:NotBlank(message = "Budget name is required")
    val name: String,

    @field:PositiveOrZero(message = "Budget amount must be zero or positive")
    val amount: BigDecimal,

    // Optional for backwards compatibility. When omitted, the service keeps the currently
    // stored date instead of resetting it.
    val date: LocalDate? = null
)
