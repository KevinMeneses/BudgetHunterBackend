package com.budgethunter.dto

import com.budgethunter.model.EntryType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

data class UpdateBudgetEntryRequest(
    @field:NotNull(message = "Amount is required")
    val amount: BigDecimal,

    @field:NotBlank(message = "Description is required")
    val description: String,

    @field:NotBlank(message = "Category is required")
    val category: String,

    @field:NotNull(message = "Entry type is required")
    val type: EntryType
)
