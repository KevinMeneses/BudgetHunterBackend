package com.budgethunter.service

import com.budgethunter.dto.*
import com.budgethunter.model.Budget
import com.budgethunter.model.BudgetEntry
import com.budgethunter.model.UserBudget
import com.budgethunter.model.UserBudgetId
import com.budgethunter.repository.BudgetEntryRepository
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class BudgetService(
    private val budgetRepository: BudgetRepository,
    private val userBudgetRepository: UserBudgetRepository,
    private val userRepository: UserRepository,
    private val budgetEntryRepository: BudgetEntryRepository,
    private val sseService: SseService
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
        verifyUserHasAccessToBudget(request.budgetId, authenticatedUserEmail)

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
        verifyUserHasAccessToBudget(budgetId, authenticatedUserEmail)

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

    @Transactional
    fun putEntry(request: PutEntryRequest, authenticatedUserEmail: String): BudgetEntryResponse {
        verifyUserHasAccessToBudget(request.budgetId, authenticatedUserEmail)

        val budget = budgetRepository.findById(request.budgetId)
            .orElseThrow { IllegalArgumentException("Budget not found with id: ${request.budgetId}") }

        val user = userRepository.findById(authenticatedUserEmail)
            .orElseThrow { IllegalArgumentException("User not found with email: $authenticatedUserEmail") }

        val budgetEntry = if (request.id != null) {
            updateExistingEntry(request, budget, user)
        } else {
            createNewEntry(request, budget, user)
        }

        val response = budgetEntry.toResponse()

        broadcastBudgetEntryEvent(budgetEntry, user)

        return response
    }

    private fun createNewEntry(request: PutEntryRequest, budget: Budget, user: com.budgethunter.model.User): BudgetEntry {
        val newEntry = BudgetEntry(
            budget = budget,
            amount = request.amount,
            description = request.description,
            category = request.category,
            type = request.type,
            createdBy = user,
            creationDate = LocalDateTime.now(),
            modificationDate = LocalDateTime.now()
        )

        return budgetEntryRepository.save(newEntry)
    }

    private fun updateExistingEntry(request: PutEntryRequest, budget: Budget, user: com.budgethunter.model.User): BudgetEntry {
        val existingEntry = budgetEntryRepository.findById(request.id!!)
            .orElseThrow { IllegalArgumentException("Budget entry not found with id: ${request.id}") }

        if (existingEntry.budget.id != budget.id) {
            throw IllegalArgumentException("Budget entry ${request.id} does not belong to budget ${budget.id}")
        }

        val updatedEntry = existingEntry.copy(
            amount = request.amount,
            description = request.description,
            category = request.category,
            type = request.type,
            updatedBy = user,
            modificationDate = LocalDateTime.now()
        )

        return budgetEntryRepository.save(updatedEntry)
    }

    private fun BudgetEntry.toResponse() = BudgetEntryResponse(
        id = this.id!!,
        budgetId = this.budget.id!!,
        amount = this.amount,
        description = this.description,
        category = this.category,
        type = this.type,
        createdByEmail = this.createdBy?.email,
        updatedByEmail = this.updatedBy?.email,
        creationDate = this.creationDate,
        modificationDate = this.modificationDate
    )

    fun verifyUserHasAccessToBudget(budgetId: Long, userEmail: String) {
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = userEmail)
        if (!userBudgetRepository.existsById(userBudgetId)) {
            throw IllegalArgumentException("You don't have access to budget with id: $budgetId")
        }
    }

    private fun broadcastBudgetEntryEvent(budgetEntry: BudgetEntry, user: com.budgethunter.model.User) {
        val event = BudgetEntryEvent(
            budgetEntry = BudgetEntryEventData(
                id = budgetEntry.id!!,
                budgetId = budgetEntry.budget.id!!,
                amount = budgetEntry.amount,
                description = budgetEntry.description,
                category = budgetEntry.category,
                type = budgetEntry.type,
                creationDate = budgetEntry.creationDate,
                modificationDate = budgetEntry.modificationDate
            ),
            userInfo = UserEventInfo(
                email = user.email,
                name = user.name
            )
        )

        sseService.broadcastBudgetEntryEvent(budgetEntry.budget.id!!, event)
    }
}
