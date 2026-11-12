package com.budgethunter.repository

import com.budgethunter.model.Budget
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface BudgetRepository : JpaRepository<Budget, Long> {

    @Query("SELECT b.id FROM Budget b WHERE b.ownerEmail = :ownerEmail")
    fun findIdsByOwnerEmail(@Param("ownerEmail") ownerEmail: String): List<Long>

    /**
     * The budget, with its row locked until the transaction ends: shared, for a change of membership that depends
     * on who owns it (a member leaving, the owner removing someone), so that it cannot interleave with an owner
     * change, which takes the exclusive lock ([findByIdForOwnerChange]).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT b FROM Budget b WHERE b.id = :budgetId")
    fun findByIdForMembershipChange(@Param("budgetId") budgetId: Long): Budget?

    /** The budget, with its row locked exclusively until the transaction ends; see [findByIdForMembershipChange]. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Budget b WHERE b.id = :budgetId")
    fun findByIdForOwnerChange(@Param("budgetId") budgetId: Long): Budget?

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
