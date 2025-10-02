package com.budgethunter.model

import jakarta.persistence.*
import java.io.Serializable

@Entity
@Table(name = "user_budgets")
data class UserBudget(
    @field:EmbeddedId
    val id: UserBudgetId = UserBudgetId(),

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:MapsId("budgetId")
    @field:JoinColumn(name = "budget_id", nullable = false)
    val budget: Budget,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:MapsId("userEmail")
    @field:JoinColumn(name = "user_email", nullable = false)
    val user: User
)

@Embeddable
data class UserBudgetId(
    @field:Column(name = "budget_id")
    val budgetId: Long? = null,

    @field:Column(name = "user_email")
    val userEmail: String = ""
) : Serializable
