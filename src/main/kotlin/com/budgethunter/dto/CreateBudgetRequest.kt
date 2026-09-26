package com.budgethunter.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal
import java.time.LocalDate

data class CreateBudgetRequest(
    @field:NotBlank(message = "Budget name is required")
    val name: String,

    @field:PositiveOrZero(message = "Budget amount must be zero or positive")
    val amount: BigDecimal,

    // Optional for backwards compatibility with older app builds that don't send it yet.
    // When omitted, the service defaults it to today's date.
    val date: LocalDate? = null
)
