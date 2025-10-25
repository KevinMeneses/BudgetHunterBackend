package com.budgethunter.repository

import com.budgethunter.model.BudgetEntry
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface BudgetEntryRepository : JpaRepository<BudgetEntry, Long> {

    @Query("SELECT be FROM BudgetEntry be WHERE be.budget.id = :budgetId ORDER BY be.modificationDate DESC")
    fun findByBudgetId(@Param("budgetId") budgetId: Long): List<BudgetEntry>

    fun deleteByBudgetId(budgetId: Long)
}
