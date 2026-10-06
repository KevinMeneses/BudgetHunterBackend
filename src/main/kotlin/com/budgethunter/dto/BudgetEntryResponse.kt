package com.budgethunter.dto

import com.budgethunter.model.CategorySource
import com.budgethunter.model.EntryType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class BudgetEntryResponse(
    val id: Long,
    val budgetId: Long,
    val amount: BigDecimal,
    val description: String,
    val category: String,
    // Additive: lets a client show an automatic category differently and tell it apart from its own.
    val categorySource: CategorySource = CategorySource.USER,
    val type: EntryType,
    val createdByEmail: String?,
    val updatedByEmail: String?,
    val creationDate: LocalDateTime,
    val modificationDate: LocalDateTime,
    val date: LocalDate? = null
)
