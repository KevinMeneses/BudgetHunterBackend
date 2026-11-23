package com.budgethunter.repository

import com.budgethunter.model.Budget
import com.budgethunter.model.User
import com.budgethunter.model.UserBudget
import com.budgethunter.model.UserBudgetId
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
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

    /** Members of the budget besides [ownerEmail]: its collaborators, for the sharing limits. */
    @Query("SELECT COUNT(ub) FROM UserBudget ub WHERE ub.id.budgetId = :budgetId AND ub.id.userEmail <> :ownerEmail")
    fun countOthersThanOwner(@Param("budgetId") budgetId: Long, @Param("ownerEmail") ownerEmail: String): Long

    @Query(
        "SELECT ub.id.budgetId AS budgetId, COUNT(ub) AS total FROM UserBudget ub " +
            "WHERE ub.id.budgetId IN :budgetIds GROUP BY ub.id.budgetId"
    )
    fun countByBudgetIds(@Param("budgetIds") budgetIds: Collection<Long>): List<BudgetCollaboratorCount>

    fun deleteByBudgetId(budgetId: Long)

    /**
     * Cuts [email]'s account off from everyone else: removes the other members of the budgets it owns and its own
     * membership in budgets someone else owns. Its own budgets, entries and its membership in them stay. Used when a
     * Google link shows the account may have been set up by someone else (UserService.linkGoogle). Subqueries rather
     * than PostgreSQL's DELETE ... USING, which H2 (tests) lacks.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "DELETE FROM user_budgets WHERE " +
            "(user_email <> :email AND budget_id IN (SELECT id FROM budgets WHERE owner_email = :email)) OR " +
            "(user_email = :email AND budget_id IN (SELECT id FROM budgets WHERE owner_email <> :email))",
        nativeQuery = true
    )
    fun deleteMembershipsSharedWithOthers(@Param("email") email: String): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM UserBudget ub WHERE ub.id.userEmail = :userEmail")
    fun deleteByUserEmail(@Param("userEmail") userEmail: String): Int
}

/** Projection of [UserBudgetRepository.countByBudgetIds]. */
interface BudgetCollaboratorCount {
    val budgetId: Long
    val total: Long
}
