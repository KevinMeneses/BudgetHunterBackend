package com.budgethunter.dto

data class CollaboratorResponse(
    val budgetId: Long,
    val budgetName: String,
    val collaboratorEmail: String,
    val collaboratorName: String
)
