package com.budgethunter.dto

import com.budgethunter.model.EntryType
import java.math.BigDecimal
import java.time.LocalDateTime

data class BudgetEntryResponse(
    val id: Long,
    val budgetId: Long,
    val amount: BigDecimal,
    val description: String,
    val category: String,
    val type: EntryType,
    val createdByEmail: String?,
    val updatedByEmail: String?,
    val creationDate: LocalDateTime,
    val modificationDate: LocalDateTime
)
