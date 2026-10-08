package com.budgethunter.repository

import com.budgethunter.model.BudgetEntry
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/** The id and description of an entry, which is all that categorising it needs. */
interface PendingCategorization {
    val id: Long
    val description: String
}

@Repository
interface BudgetEntryRepository : JpaRepository<BudgetEntry, Long> {

    @Query("SELECT be FROM BudgetEntry be WHERE be.budget.id = :budgetId ORDER BY be.modificationDate DESC")
    fun findByBudgetId(@Param("budgetId") budgetId: Long): List<BudgetEntry>

    @Query("SELECT be FROM BudgetEntry be WHERE be.budget.id = :budgetId")
    fun findByBudgetId(@Param("budgetId") budgetId: Long, pageable: Pageable): Page<BudgetEntry>

    fun deleteByBudgetId(budgetId: Long)

    /**
     * The entries of a budget still waiting for an automatic category: automatic, still holding the
     * [placeholder], and created by someone whose AI processing is on **right now**, so turning it off
     * keeps their descriptions away from the classifier even in a budget they share. Oldest first.
     * An entry with no creator (their account was deleted) is never included.
     */
    @Query(
        "SELECT e.id AS id, e.description AS description FROM BudgetEntry e " +
            "WHERE e.budget.id = :budgetId AND e.category = :placeholder " +
            "AND e.categorySource = com.budgethunter.model.CategorySource.AUTO " +
            "AND e.createdBy.aiProcessingEnabled = true ORDER BY e.id"
    )
    fun findPendingCategorization(
        @Param("budgetId") budgetId: Long,
        @Param("placeholder") placeholder: String,
        pageable: Pageable
    ): List<PendingCategorization>

    @Query(
        "SELECT COUNT(e) FROM BudgetEntry e " +
            "WHERE e.budget.id = :budgetId AND e.category = :placeholder " +
            "AND e.categorySource = com.budgethunter.model.CategorySource.AUTO " +
            "AND e.createdBy.aiProcessingEnabled = true"
    )
    fun countPendingCategorization(
        @Param("budgetId") budgetId: Long,
        @Param("placeholder") placeholder: String
    ): Long

    /**
     * Stores an automatic category, but only if the entry is still automatic and still has the
     * [description] the category was worked out from. Returns the rows changed: 0 means a person edited
     * (or deleted) the entry while the classifier was working and the category must be dropped.
     *
     * One conditional statement rather than load-modify-save, because the entry is immutable and saving a
     * stale copy would silently revert whatever the person changed in the meantime.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE BudgetEntry e SET e.category = :category, e.modificationDate = :now " +
            "WHERE e.id = :id AND e.description = :description " +
            "AND e.categorySource = com.budgethunter.model.CategorySource.AUTO"
    )
    fun applyAutoCategory(
        @Param("id") id: Long,
        @Param("description") description: String,
        @Param("category") category: String,
        @Param("now") now: LocalDateTime
    ): Int
}
