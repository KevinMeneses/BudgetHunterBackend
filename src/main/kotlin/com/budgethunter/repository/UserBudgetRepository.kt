package com.budgethunter.repository

import com.budgethunter.model.Budget
import com.budgethunter.model.User
import com.budgethunter.model.UserBudget
import com.budgethunter.model.UserBudgetId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface UserBudgetRepository : JpaRepository<UserBudget, UserBudgetId> {

    @Query("SELECT ub.budget FROM UserBudget ub WHERE ub.id.userEmail = :userEmail")
    fun findBudgetsByUserEmail(@Param("userEmail") userEmail: String): List<Budget>

    @Query("SELECT ub.user FROM UserBudget ub WHERE ub.id.budgetId = :budgetId")
    fun findUsersByBudgetId(@Param("budgetId") budgetId: Long): List<User>

    @Query("SELECT COUNT(ub) FROM UserBudget ub WHERE ub.id.budgetId = :budgetId")
    fun countByBudgetId(@Param("budgetId") budgetId: Long): Long

    fun deleteByBudgetId(budgetId: Long)
}
