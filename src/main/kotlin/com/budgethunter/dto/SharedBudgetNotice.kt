package com.budgethunter.dto

/**
 * A budget the account shares with someone else, as listed in [SignInResponse.sharedBudgets] when a Google link
 * removed a password of unknown origin: if the user does not recognise it, the app tells them to remove it.
 */
data class SharedBudgetNotice(
    val budgetId: Long,
    val name: String,
    val ownerEmail: String,
    // Every member besides the account itself, the owner included when it is someone else.
    val otherMembers: List<String>
)
