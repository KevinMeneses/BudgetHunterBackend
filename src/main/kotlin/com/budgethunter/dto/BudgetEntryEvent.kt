package com.budgethunter.dto

data class BudgetEntryEvent(
    val budgetId: Long,
    val entryId: Long,
    val action: BudgetEntryAction,
    val userInfo: UserEventInfo
)

enum class BudgetEntryAction {
    CREATED,
    UPDATED,
    DELETED
}

data class UserEventInfo(
    val email: String,
    val name: String
)
