package com.budgethunter.dto

import com.budgethunter.model.EntryType
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate

data class UpdateBudgetEntryRequest(
    @field:NotNull(message = "Amount is required")
    val amount: BigDecimal,

    // Optional: the app lets users save entries without one and sends an empty string.
    val description: String = "",

    // Optional: absent or blank means "categorise it for me". Anything else is the user's own choice
    // and is never overwritten automatically.
    @field:Size(max = 255, message = "Category must be at most 255 characters")
    val category: String? = null,

    @field:NotNull(message = "Entry type is required")
    val type: EntryType,

    // Optional for backwards compatibility. When omitted, the service keeps the currently
    // stored date instead of resetting it.
    val date: LocalDate? = null
)
