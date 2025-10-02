package com.budgethunter.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal

@Entity
@Table(name = "budgets")
data class Budget(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @field:NotBlank
    @field:Column(nullable = false)
    val name: String,

    @field:PositiveOrZero
    @field:Column(nullable = false, precision = 19, scale = 2)
    val amount: BigDecimal,

    @field:OneToMany(mappedBy = "budget", cascade = [CascadeType.ALL], orphanRemoval = true)
    val userBudgets: MutableList<UserBudget> = mutableListOf(),

    @field:OneToMany(mappedBy = "budget", cascade = [CascadeType.ALL], orphanRemoval = true)
    val budgetEntries: MutableList<BudgetEntry> = mutableListOf()
)
