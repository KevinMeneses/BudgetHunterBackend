package com.budgethunter.repository

import com.budgethunter.model.Budget
import com.budgethunter.model.User
import com.budgethunter.model.UserBudget
import com.budgethunter.model.UserBudgetId
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface UserBudgetRepository : JpaRepository<UserBudget, UserBudgetId> {

    // Newest-first: by the user-facing date descending, with budgets that have no date yet
    // (never edited since the date column was added) sorted last, then by id descending as a
    // tiebreaker (covers same-day entries and the pre-migration rows that are all NULL here).
    @Query(
        "SELECT ub.budget FROM UserBudget ub WHERE ub.id.userEmail = :userEmail " +
            "ORDER BY ub.budget.date DESC NULLS LAST, ub.budget.id DESC"
    )
    fun findBudgetsByUserEmail(@Param("userEmail") userEmail: String): List<Budget>

    @Query("SELECT ub.budget FROM UserBudget ub WHERE ub.id.userEmail = :userEmail")
    fun findBudgetsByUserEmail(@Param("userEmail") userEmail: String, pageable: Pageable): Page<Budget>

    @Query("SELECT ub.user FROM UserBudget ub WHERE ub.id.budgetId = :budgetId")
    fun findUsersByBudgetId(@Param("budgetId") budgetId: Long): List<User>

    @Query("SELECT COUNT(ub) FROM UserBudget ub WHERE ub.id.budgetId = :budgetId")
    fun countByBudgetId(@Param("budgetId") budgetId: Long): Long

    fun deleteByBudgetId(budgetId: Long)
}
