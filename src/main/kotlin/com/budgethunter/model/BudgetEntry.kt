package com.budgethunter.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDate
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

    // May be empty: description is optional in the app.
    @field:Column(nullable = false)
    val description: String,

    @field:NotBlank
    @field:Column(nullable = false)
    val category: String,

    // Whether a person or the server chose [category]. Automatic categorisation only ever touches AUTO.
    @field:NotNull
    @field:Column(name = "category_source", nullable = false)
    @field:Enumerated(EnumType.STRING)
    val categorySource: CategorySource = CategorySource.USER,

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
    val modificationDate: LocalDateTime = LocalDateTime.now(),

    // Nullable on purpose: the user-facing calendar date the app shows for this entry.
    // Existing rows stay NULL rather than being backfilled from creationDate - see
    // database/migrations/002_add_entity_dates.sql for why.
    @field:Column(name = "date")
    val date: LocalDate? = null
)

enum class EntryType {
    INCOME,
    OUTCOME
}

enum class CategorySource {
    /** Chosen by a person, or taken from a receipt they scanned. Never overwritten automatically. */
    USER,

    /** Assigned by the server from the description, and still eligible for re-categorisation. */
    AUTO
}

object EntryCategory {
    /** The fallback category: what an entry holds while it waits for, or lacks, a better one. */
    const val OTHER = "OTHER"

    /**
     * The closed list automatic categorisation chooses from. It mirrors the app's
     * `BudgetEntry.Category`; change one side and the other has to follow. Stored categories are
     * still free text (a person may type anything), this list only bounds what the server assigns.
     */
    val ALL: List<String> = listOf(
        "FOOD",
        "GROCERIES",
        "SELF_CARE",
        "TRANSPORTATION",
        "HOUSEHOLD_ITEMS",
        "SERVICES",
        "EDUCATION",
        "HEALTH",
        "LEISURE",
        "TAXES",
        OTHER
    )

    fun isKnown(category: String): Boolean = category in ALL
}
