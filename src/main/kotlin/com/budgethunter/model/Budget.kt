package com.budgethunter.model

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal
import java.time.LocalDate

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
    val budgetEntries: MutableList<BudgetEntry> = mutableListOf(),

    // Nullable on purpose: the user-facing calendar date the app shows for this budget. Rows that
    // predate this column only get an approximation (the date of their earliest entry) and stay
    // NULL when they have no entries - see database/migrations/002_add_entity_dates.sql.
    @field:Column(name = "date")
    val date: LocalDate? = null
)
