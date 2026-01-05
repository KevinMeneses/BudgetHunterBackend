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
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class BudgetService(
    private val budgetRepository: BudgetRepository,
    private val userBudgetRepository: UserBudgetRepository,
    private val userRepository: UserRepository,
    private val budgetEntryRepository: BudgetEntryRepository,
    private val sseService: SseService,
    private val reactiveSseService: ReactiveSseService
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

    @Transactional(readOnly = true)
    fun getBudgetsByUserEmail(userEmail: String, page: Int, size: Int, sortBy: String = "id", sortDirection: String = "asc"): PageResponse<BudgetResponse> {
        val sort = if (sortDirection.lowercase() == "desc") {
            Sort.by(sortBy).descending()
        } else {
            Sort.by(sortBy).ascending()
        }

        val pageable = PageRequest.of(page, size, sort)
        val budgetsPage = userBudgetRepository.findBudgetsByUserEmail(userEmail, pageable)

        return budgetsPage.toPageResponse { budget ->
            BudgetResponse(
                id = budget.id!!,
                name = budget.name,
                amount = budget.amount
            )
        }
    }

    @Transactional
    fun addCollaborator(budgetId: Long, request: AddCollaboratorRequest, authenticatedUserEmail: String): CollaboratorResponse {
        verifyUserHasAccessToBudget(budgetId, authenticatedUserEmail)

        val budget = budgetRepository.findById(budgetId)
            .orElseThrow { IllegalArgumentException("Budget not found with id: $budgetId") }

        val collaborator = userRepository.findById(request.email)
            .orElseThrow { IllegalArgumentException("User not found with email: ${request.email}") }

        val userBudgetId = UserBudgetId(
            budgetId = budgetId,
            userEmail = request.email
        )

        if (userBudgetRepository.existsById(userBudgetId)) {
            throw IllegalStateException("User ${request.email} is already a collaborator on budget $budgetId")
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

    @Transactional(readOnly = true)
    fun getEntriesByBudgetId(budgetId: Long, authenticatedUserEmail: String): List<BudgetEntryResponse> {
        verifyUserHasAccessToBudget(budgetId, authenticatedUserEmail)

        if (!budgetRepository.existsById(budgetId)) {
            throw IllegalArgumentException("Budget not found with id: $budgetId")
        }

        val entries = budgetEntryRepository.findByBudgetId(budgetId)
        return entries.map { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun getEntriesByBudgetId(budgetId: Long, authenticatedUserEmail: String, page: Int, size: Int, sortBy: String = "modificationDate", sortDirection: String = "desc"): PageResponse<BudgetEntryResponse> {
        verifyUserHasAccessToBudget(budgetId, authenticatedUserEmail)

        if (!budgetRepository.existsById(budgetId)) {
            throw IllegalArgumentException("Budget not found with id: $budgetId")
        }

        val sort = if (sortDirection.lowercase() == "desc") {
            Sort.by(sortBy).descending()
        } else {
            Sort.by(sortBy).ascending()
        }

        val pageable = PageRequest.of(page, size, sort)
        val entriesPage = budgetEntryRepository.findByBudgetId(budgetId, pageable)

        return entriesPage.toPageResponse { it.toResponse() }
    }

    @Transactional
    fun createEntry(budgetId: Long, request: CreateBudgetEntryRequest, authenticatedUserEmail: String): BudgetEntryResponse {
        verifyUserHasAccessToBudget(budgetId, authenticatedUserEmail)

        val budget = budgetRepository.findById(budgetId)
            .orElseThrow { IllegalArgumentException("Budget not found with id: $budgetId") }

        val user = userRepository.findById(authenticatedUserEmail)
            .orElseThrow { IllegalArgumentException("User not found with email: $authenticatedUserEmail") }

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

        val savedEntry = budgetEntryRepository.save(newEntry)
        val response = savedEntry.toResponse()

        broadcastBudgetEntryEvent(savedEntry, user, BudgetEntryAction.CREATED)

        return response
    }

    @Transactional
    fun updateEntry(budgetId: Long, entryId: Long, request: UpdateBudgetEntryRequest, authenticatedUserEmail: String): BudgetEntryResponse {
        verifyUserHasAccessToBudget(budgetId, authenticatedUserEmail)

        val user = userRepository.findById(authenticatedUserEmail)
            .orElseThrow { IllegalArgumentException("User not found with email: $authenticatedUserEmail") }

        val existingEntry = budgetEntryRepository.findById(entryId)
            .orElseThrow { IllegalArgumentException("Budget entry not found with id: $entryId") }

        if (existingEntry.budget.id != budgetId) {
            throw IllegalArgumentException("Budget entry $entryId does not belong to budget $budgetId")
        }

        val updatedEntry = existingEntry.copy(
            amount = request.amount,
            description = request.description,
            category = request.category,
            type = request.type,
            updatedBy = user,
            modificationDate = LocalDateTime.now()
        )

        val savedEntry = budgetEntryRepository.save(updatedEntry)
        val response = savedEntry.toResponse()

        broadcastBudgetEntryEvent(savedEntry, user, BudgetEntryAction.UPDATED)

        return response
    }

    @Transactional
    fun putEntry(request: PutEntryRequest, authenticatedUserEmail: String): BudgetEntryResponse {
        verifyUserHasAccessToBudget(request.budgetId, authenticatedUserEmail)

        val budget = budgetRepository.findById(request.budgetId)
            .orElseThrow { IllegalArgumentException("Budget not found with id: ${request.budgetId}") }

        val user = userRepository.findById(authenticatedUserEmail)
            .orElseThrow { IllegalArgumentException("User not found with email: $authenticatedUserEmail") }

        val isUpdate = request.id != null
        val budgetEntry = if (isUpdate) {
            updateExistingEntry(request, budget, user)
        } else {
            createNewEntry(request, budget, user)
        }

        val response = budgetEntry.toResponse()

        val action = if (isUpdate) BudgetEntryAction.UPDATED else BudgetEntryAction.CREATED
        broadcastBudgetEntryEvent(budgetEntry, user, action)

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

    @Transactional
    fun deleteBudget(budgetId: Long, authenticatedUserEmail: String) {
        verifyUserHasAccessToBudget(budgetId, authenticatedUserEmail)

        if (!budgetRepository.existsById(budgetId)) {
            throw IllegalArgumentException("Budget not found with id: $budgetId")
        }

        // Delete all budget entries first (due to foreign key constraints)
        budgetEntryRepository.deleteByBudgetId(budgetId)

        // Delete all user-budget associations
        userBudgetRepository.deleteByBudgetId(budgetId)

        // Finally delete the budget itself
        budgetRepository.deleteById(budgetId)
    }

    @Transactional
    fun deleteEntry(budgetId: Long, entryId: Long, authenticatedUserEmail: String) {
        verifyUserHasAccessToBudget(budgetId, authenticatedUserEmail)

        val entry = budgetEntryRepository.findById(entryId)
            .orElseThrow { IllegalArgumentException("Budget entry not found with id: $entryId") }

        if (entry.budget.id != budgetId) {
            throw IllegalArgumentException("Budget entry $entryId does not belong to budget $budgetId")
        }

        val user = userRepository.findById(authenticatedUserEmail)
            .orElseThrow { IllegalArgumentException("User not found with email: $authenticatedUserEmail") }

        budgetEntryRepository.deleteById(entryId)

        broadcastBudgetEntryEvent(entry, user, BudgetEntryAction.DELETED)
    }

    @Transactional
    fun removeCollaborator(budgetId: Long, collaboratorEmail: String, authenticatedUserEmail: String) {
        verifyUserHasAccessToBudget(budgetId, authenticatedUserEmail)

        if (!budgetRepository.existsById(budgetId)) {
            throw IllegalArgumentException("Budget not found with id: $budgetId")
        }

        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = collaboratorEmail)

        if (!userBudgetRepository.existsById(userBudgetId)) {
            throw IllegalArgumentException("User $collaboratorEmail is not a collaborator on budget $budgetId")
        }

        // Check if this is the last collaborator
        val collaboratorsCount = userBudgetRepository.countByBudgetId(budgetId)
        if (collaboratorsCount <= 1) {
            throw IllegalStateException("Cannot remove the last collaborator from budget $budgetId")
        }

        userBudgetRepository.deleteById(userBudgetId)
    }

    @Transactional(readOnly = true)
    fun verifyUserHasAccessToBudget(budgetId: Long, userEmail: String) {
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = userEmail)
        if (!userBudgetRepository.existsById(userBudgetId)) {
            throw IllegalArgumentException("You don't have access to budget with id: $budgetId")
        }
    }

    private fun broadcastBudgetEntryEvent(budgetEntry: BudgetEntry, user: com.budgethunter.model.User, action: BudgetEntryAction) {
        val event = BudgetEntryEvent(
            budgetId = budgetEntry.budget.id!!,
            entryId = budgetEntry.id!!,
            action = action,
            userInfo = UserEventInfo(
                email = user.email,
                name = user.name
            )
        )

        // Broadcast to both old and new SSE implementations
        sseService.broadcastBudgetEntryEvent(budgetEntry.budget.id, event)
        reactiveSseService.broadcastEvent(budgetEntry.budget.id, event)
    }

    private fun <T, R> Page<T>.toPageResponse(transform: (T) -> R): PageResponse<R> {
        return PageResponse(
            content = this.content.map(transform),
            page = this.number,
            size = this.size,
            totalElements = this.totalElements,
            totalPages = this.totalPages,
            isFirst = this.isFirst,
            isLast = this.isLast
        )
    }
}
