package com.budgethunter.repository

import com.budgethunter.model.Budget
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface BudgetRepository : JpaRepository<Budget, Long> {

    fun findByOwnerEmail(ownerEmail: String): List<Budget>

    /**
     * Who takes a budget over from [leavingEmail]: among its other members, the one who created its oldest
     * entry, or else the lowest email. Null when nobody else is a member. The same rule V6__add_budget_owner.sql
     * used to pick the owners of the budgets that existed before ownership did.
     */
    @Query(
        value = "SELECT COALESCE(" +
            "(SELECT be.created_by FROM budget_entries be " +
            "JOIN user_budgets ub ON ub.budget_id = be.budget_id AND ub.user_email = be.created_by " +
            "WHERE be.budget_id = :budgetId AND be.created_by <> :leavingEmail " +
            "ORDER BY be.creation_date, be.id LIMIT 1), " +
            "(SELECT MIN(ub.user_email) FROM user_budgets ub " +
            "WHERE ub.budget_id = :budgetId AND ub.user_email <> :leavingEmail))",
        nativeQuery = true
    )
    fun findSuccessorOwner(@Param("budgetId") budgetId: Long, @Param("leavingEmail") leavingEmail: String): String?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Budget b SET b.ownerEmail = :ownerEmail WHERE b.id = :budgetId")
    fun updateOwner(@Param("budgetId") budgetId: Long, @Param("ownerEmail") ownerEmail: String): Int
}
