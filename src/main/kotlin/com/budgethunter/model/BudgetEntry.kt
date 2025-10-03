package com.budgethunter.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "budget_entries")
data class BudgetEntry(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "budget_id", nullable = false)
    @field:NotNull
    val budget: Budget,

    @field:NotNull
    @field:Column(nullable = false, precision = 19, scale = 2)
    val amount: BigDecimal,

    @field:NotBlank
    @field:Column(nullable = false)
    val description: String,

    @field:NotBlank
    @field:Column(nullable = false)
    val category: String,

    @field:NotNull
    @field:Column(nullable = false)
    @field:Enumerated(EnumType.STRING)
    val type: EntryType,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "created_by")
    val createdBy: User? = null,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "updated_by")
    val updatedBy: User? = null,

    @field:Column(nullable = false, updatable = false)
    val creationDate: LocalDateTime = LocalDateTime.now(),

    @field:Column(nullable = false)
    val modificationDate: LocalDateTime = LocalDateTime.now()
)

enum class EntryType {
    INCOME,
    OUTCOME
}
