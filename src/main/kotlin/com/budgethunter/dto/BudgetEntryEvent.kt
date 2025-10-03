package com.budgethunter.dto

import com.budgethunter.model.EntryType
import java.math.BigDecimal
import java.time.LocalDateTime

data class BudgetEntryEvent(
    val budgetEntry: BudgetEntryEventData,
    val userInfo: UserEventInfo
)

data class BudgetEntryEventData(
    val id: Long,
    val budgetId: Long,
    val amount: BigDecimal,
    val description: String,
    val category: String,
    val type: EntryType,
    val creationDate: LocalDateTime,
    val modificationDate: LocalDateTime
)

data class UserEventInfo(
    val email: String,
    val name: String
)
