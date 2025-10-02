package com.budgethunter.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "budget_entries")
data class BudgetEntry(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_id", nullable = false)
    @NotNull
    val budget: Budget,

    @NotNull
    @Column(nullable = false, precision = 19, scale = 2)
    val amount: BigDecimal,

    @NotBlank
    @Column(nullable = false)
    val description: String,

    @NotBlank
    @Column(nullable = false)
    val category: String,

    @NotBlank
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val type: EntryType,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    val createdBy: User? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    val updatedBy: User? = null,

    @Column(nullable = false, updatable = false)
    val creationDate: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    val modificationDate: LocalDateTime = LocalDateTime.now()
)

enum class EntryType {
    INCOME,
    EXPENSE
}
