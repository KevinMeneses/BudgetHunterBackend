package com.budgethunter.model

import jakarta.persistence.*
import java.io.Serializable

@Entity
@Table(name = "user_budgets")
data class UserBudget(
    @EmbeddedId
    val id: UserBudgetId = UserBudgetId(),

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("budgetId")
    @JoinColumn(name = "budget_id", nullable = false)
    val budget: Budget,

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userEmail")
    @JoinColumn(name = "user_email", nullable = false)
    val user: User
)

@Embeddable
data class UserBudgetId(
    @Column(name = "budget_id")
    val budgetId: Long? = null,

    @Column(name = "user_email")
    val userEmail: String = ""
) : Serializable
