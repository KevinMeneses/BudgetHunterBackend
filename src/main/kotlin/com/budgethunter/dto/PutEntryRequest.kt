package com.budgethunter.dto

import com.budgethunter.model.EntryType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal

data class PutEntryRequest(
    val id: Long? = null,

    @field:NotNull(message = "Budget ID is required")
    @field:Positive(message = "Budget ID must be positive")
    val budgetId: Long,

    @field:NotNull(message = "Amount is required")
    val amount: BigDecimal,

    @field:NotBlank(message = "Description is required")
    val description: String,

    @field:NotBlank(message = "Category is required")
    val category: String,

    @field:NotNull(message = "Entry type is required")
    val type: EntryType
)
