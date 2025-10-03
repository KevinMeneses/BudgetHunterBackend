package com.budgethunter.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

data class AddCollaboratorRequest(
    @field:NotNull(message = "Budget ID is required")
    @field:Positive(message = "Budget ID must be positive")
    val budgetId: Long,

    @field:NotNull(message = "Email is required")
    @field:Email(message = "Email must be valid")
    val email: String
)
