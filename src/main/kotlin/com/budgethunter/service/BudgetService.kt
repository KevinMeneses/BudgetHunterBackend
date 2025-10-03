package com.budgethunter.service

import com.budgethunter.dto.AddCollaboratorRequest
import com.budgethunter.dto.BudgetResponse
import com.budgethunter.dto.CollaboratorResponse
import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.UserResponse
import com.budgethunter.model.Budget
import com.budgethunter.model.UserBudget
import com.budgethunter.model.UserBudgetId
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BudgetService(
    private val budgetRepository: BudgetRepository,
    private val userBudgetRepository: UserBudgetRepository,
    private val userRepository: UserRepository
) {

    @Transactional
    fun createBudget(request: CreateBudgetRequest, userEmail: String): BudgetResponse {
        val user = userRepository.findById(userEmail)
            .orElseThrow { IllegalArgumentException("User not found") }

        val budget = Budget(
            name = request.name,
            amount = request.amount
        )

        val savedBudget = budgetRepository.save(budget)

        val userBudget = UserBudget(
            id = UserBudgetId(
                budgetId = savedBudget.id,
                userEmail = userEmail
            ),
            budget = savedBudget,
            user = user
        )

        userBudgetRepository.save(userBudget)

        return BudgetResponse(
            id = savedBudget.id!!,
            name = savedBudget.name,
            amount = savedBudget.amount
        )
    }

    @Transactional(readOnly = true)
    fun getBudgetsByUserEmail(userEmail: String): List<BudgetResponse> {
        val budgets = userBudgetRepository.findBudgetsByUserEmail(userEmail)
        return budgets.map { budget ->
            BudgetResponse(
                id = budget.id!!,
                name = budget.name,
                amount = budget.amount
            )
        }
    }

    @Transactional
    fun addCollaborator(request: AddCollaboratorRequest, authenticatedUserEmail: String): CollaboratorResponse {
        verifyAuthenticatedUserHasAccessToBudget(request.budgetId, authenticatedUserEmail)

        val budget = budgetRepository.findById(request.budgetId)
            .orElseThrow { IllegalArgumentException("Budget not found with id: ${request.budgetId}") }

        val collaborator = userRepository.findById(request.email)
            .orElseThrow { IllegalArgumentException("User not found with email: ${request.email}") }

        val userBudgetId = UserBudgetId(
            budgetId = request.budgetId,
            userEmail = request.email
        )

        if (userBudgetRepository.existsById(userBudgetId)) {
            throw IllegalStateException("User ${request.email} is already a collaborator on budget ${request.budgetId}")
        }

        val userBudget = UserBudget(
            id = userBudgetId,
            budget = budget,
            user = collaborator
        )

        userBudgetRepository.save(userBudget)

        return CollaboratorResponse(
            budgetId = budget.id!!,
            budgetName = budget.name,
            collaboratorEmail = collaborator.email,
            collaboratorName = collaborator.name
        )
    }

    @Transactional(readOnly = true)
    fun getCollaboratorsByBudgetId(budgetId: Long, authenticatedUserEmail: String): List<UserResponse> {
        verifyAuthenticatedUserHasAccessToBudget(budgetId, authenticatedUserEmail)

        if (!budgetRepository.existsById(budgetId)) {
            throw IllegalArgumentException("Budget not found with id: $budgetId")
        }

        val users = userBudgetRepository.findUsersByBudgetId(budgetId)
        return users.map { user ->
            UserResponse(
                email = user.email,
                name = user.name
            )
        }
    }

    private fun verifyAuthenticatedUserHasAccessToBudget(budgetId: Long, userEmail: String) {
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = userEmail)
        if (!userBudgetRepository.existsById(userBudgetId)) {
            throw IllegalArgumentException("You don't have access to budget with id: $budgetId")
        }
    }
}
