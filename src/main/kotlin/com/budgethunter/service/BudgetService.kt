package com.budgethunter.service

import com.budgethunter.dto.AddCollaboratorRequest
import com.budgethunter.dto.BudgetEntryAction
import com.budgethunter.dto.BudgetEntryEvent
import com.budgethunter.dto.BudgetEntryResponse
import com.budgethunter.dto.BudgetResponse
import com.budgethunter.dto.CollaboratorResponse
import com.budgethunter.dto.CreateBudgetEntryRequest
import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.PageResponse
import com.budgethunter.dto.PutEntryRequest
import com.budgethunter.dto.UpdateBudgetEntryRequest
import com.budgethunter.dto.UpdateBudgetRequest
import com.budgethunter.dto.UserEventInfo
import com.budgethunter.dto.UserResponse
import com.budgethunter.exception.ForbiddenAccessException
import com.budgethunter.exception.ResourceNotFoundException
import com.budgethunter.model.Budget
import com.budgethunter.model.BudgetEntry
import com.budgethunter.model.EntryCategory
import com.budgethunter.model.User
import com.budgethunter.model.UserBudget
import com.budgethunter.model.UserBudgetId
import com.budgethunter.repository.BudgetEntryRepository
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

@Service
class BudgetService(
    private val budgetRepository: BudgetRepository,
    private val userBudgetRepository: UserBudgetRepository,
    private val userRepository: UserRepository,
    private val budgetEntryRepository: BudgetEntryRepository,
    private val reactiveSseService: ReactiveSseService
) {

    @Transactional
    fun createBudget(request: CreateBudgetRequest, userEmail: String): BudgetResponse {
        val user = authenticatedUser(userEmail)

        val budget = Budget(
            name = request.name,
            amount = request.amount,
            date = request.date ?: LocalDate.now()
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
            amount = savedBudget.amount,
            date = savedBudget.date,
            collaboratorCount = 1
        )
    }

    @Transactional(readOnly = true)
    fun getBudgetsByUserEmail(userEmail: String): List<BudgetResponse> {
        val budgets = userBudgetRepository.findBudgetsByUserEmail(userEmail)
        val counts = collaboratorCounts(budgets)
        return budgets.map { it.toResponse(counts) }
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

        val counts = collaboratorCounts(budgetsPage.content)
        return budgetsPage.toPageResponse { it.toResponse(counts) }
    }

    @Transactional
    fun updateBudget(budgetId: Long, request: UpdateBudgetRequest, authenticatedUserEmail: String): BudgetResponse {
        val budget = findAccessibleBudget(budgetId, authenticatedUserEmail)

        val updatedBudget = budget.copy(
            name = request.name,
            amount = request.amount,
            date = request.date ?: budget.date
        )

        val savedBudget = budgetRepository.save(updatedBudget)

        return BudgetResponse(
            id = savedBudget.id!!,
            name = savedBudget.name,
            amount = savedBudget.amount,
            date = savedBudget.date,
            collaboratorCount = userBudgetRepository.countByBudgetId(budgetId).toInt()
        )
    }

    @Transactional
    fun addCollaborator(budgetId: Long, request: AddCollaboratorRequest, authenticatedUserEmail: String): CollaboratorResponse {
        val budget = findAccessibleBudget(budgetId, authenticatedUserEmail)

        val collaborator = userRepository.findById(request.email)
            .orElseThrow { ResourceNotFoundException("User not found with email: ${request.email}") }

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
        verifyBudgetAccess(budgetId, authenticatedUserEmail)

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
        verifyBudgetAccess(budgetId, authenticatedUserEmail)

        val entries = budgetEntryRepository.findByBudgetId(budgetId)
        return entries.map { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun getEntriesByBudgetId(budgetId: Long, authenticatedUserEmail: String, page: Int, size: Int, sortBy: String = "modificationDate", sortDirection: String = "desc"): PageResponse<BudgetEntryResponse> {
        verifyBudgetAccess(budgetId, authenticatedUserEmail)

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
        val budget = findAccessibleBudget(budgetId, authenticatedUserEmail)

        val user = authenticatedUser(authenticatedUserEmail)

        val category = resolveCategory(request.category)
        val newEntry = BudgetEntry(
            budget = budget,
            amount = request.amount,
            description = request.description,
            category = category,
            type = request.type,
            createdBy = user,
            creationDate = LocalDateTime.now(),
            modificationDate = LocalDateTime.now(),
            date = request.date ?: LocalDate.now()
        )

        val savedEntry = budgetEntryRepository.save(newEntry)
        val response = savedEntry.toResponse()

        broadcastBudgetEntryEvent(savedEntry, user, BudgetEntryAction.CREATED)

        return response
    }

    @Transactional
    fun updateEntry(budgetId: Long, entryId: Long, request: UpdateBudgetEntryRequest, authenticatedUserEmail: String): BudgetEntryResponse {
        verifyBudgetAccess(budgetId, authenticatedUserEmail)

        val user = authenticatedUser(authenticatedUserEmail)
        val existingEntry = findEntryInBudget(entryId, budgetId)

        val category = resolveCategory(request.category, existingEntry)
        val updatedEntry = existingEntry.copy(
            amount = request.amount,
            description = request.description,
            category = category,
            type = request.type,
            updatedBy = user,
            modificationDate = LocalDateTime.now(),
            date = request.date ?: existingEntry.date
        )

        val savedEntry = budgetEntryRepository.save(updatedEntry)
        val response = savedEntry.toResponse()

        broadcastBudgetEntryEvent(savedEntry, user, BudgetEntryAction.UPDATED)

        return response
    }

    @Transactional
    fun putEntry(request: PutEntryRequest, authenticatedUserEmail: String): BudgetEntryResponse {
        val budget = findAccessibleBudget(request.budgetId, authenticatedUserEmail)

        val user = authenticatedUser(authenticatedUserEmail)

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

    private fun createNewEntry(request: PutEntryRequest, budget: Budget, user: User): BudgetEntry {
        val category = resolveCategory(request.category)
        val newEntry = BudgetEntry(
            budget = budget,
            amount = request.amount,
            description = request.description,
            category = category,
            type = request.type,
            createdBy = user,
            creationDate = LocalDateTime.now(),
            modificationDate = LocalDateTime.now(),
            date = request.date ?: LocalDate.now()
        )

        return budgetEntryRepository.save(newEntry)
    }

    private fun updateExistingEntry(request: PutEntryRequest, budget: Budget, user: User): BudgetEntry {
        val existingEntry = findEntryInBudget(request.id!!, budget.id!!)

        val category = resolveCategory(request.category, existingEntry)
        val updatedEntry = existingEntry.copy(
            amount = request.amount,
            description = request.description,
            category = category,
            type = request.type,
            updatedBy = user,
            modificationDate = LocalDateTime.now(),
            date = request.date ?: existingEntry.date
        )

        return budgetEntryRepository.save(updatedEntry)
    }

    /**
     * Decides which category an entry is stored with.
     *
     * A category in the request is the user's choice, except [EntryCategory.UNCATEGORIZED] ("Sin categoría"),
     * which is the absence of one. Without a choice the entry holds [EntryCategory.UNCATEGORIZED], which is all
     * that makes it eligible for automatic categorisation if the user ever asks for it. That does not depend on
     * their AI preference, which only decides whether a categorisation may run (and is checked then): an entry
     * saved while it was off must still be offered once it is on.
     *
     * On update, omitting the category leaves the stored one alone.
     */
    private fun resolveCategory(requested: String?, existing: BudgetEntry? = null): String {
        val explicit = requested?.trim()?.takeIf { it.isNotEmpty() && !it.equals(EntryCategory.UNCATEGORIZED, true) }
        return when {
            explicit != null -> explicit
            // Nothing chosen, or "Sin categoría" picked on purpose, which comes to the same.
            existing == null || !requested.isNullOrBlank() -> EntryCategory.UNCATEGORIZED
            else -> existing.category
        }
    }

    /** One grouped query for the whole list, instead of one count per budget. */
    private fun collaboratorCounts(budgets: List<Budget>): Map<Long, Int> {
        if (budgets.isEmpty()) return emptyMap()
        return userBudgetRepository.countByBudgetIds(budgets.map { it.id!! })
            .associate { it.budgetId to it.total.toInt() }
    }

    private fun Budget.toResponse(counts: Map<Long, Int>) = BudgetResponse(
        id = id!!,
        name = name,
        amount = amount,
        date = date,
        collaboratorCount = counts[id] ?: 0
    )

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
        modificationDate = this.modificationDate,
        date = this.date
    )

    @Transactional
    fun deleteBudget(budgetId: Long, authenticatedUserEmail: String) {
        verifyBudgetAccess(budgetId, authenticatedUserEmail)

        // Delete all budget entries first (due to foreign key constraints)
        budgetEntryRepository.deleteByBudgetId(budgetId)

        // Delete all user-budget associations
        userBudgetRepository.deleteByBudgetId(budgetId)

        // Finally delete the budget itself
        budgetRepository.deleteById(budgetId)
    }

    @Transactional
    fun deleteEntry(budgetId: Long, entryId: Long, authenticatedUserEmail: String) {
        verifyBudgetAccess(budgetId, authenticatedUserEmail)

        val entry = findEntryInBudget(entryId, budgetId)

        val user = authenticatedUser(authenticatedUserEmail)

        budgetEntryRepository.deleteById(entryId)

        broadcastBudgetEntryEvent(entry, user, BudgetEntryAction.DELETED)
    }

    @Transactional
    fun removeCollaborator(budgetId: Long, collaboratorEmail: String, authenticatedUserEmail: String) {
        verifyBudgetAccess(budgetId, authenticatedUserEmail)

        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = collaboratorEmail)

        if (!userBudgetRepository.existsById(userBudgetId)) {
            throw ResourceNotFoundException("User $collaboratorEmail is not a collaborator on budget $budgetId")
        }

        // Check if this is the last collaborator
        val collaboratorsCount = userBudgetRepository.countByBudgetId(budgetId)
        if (collaboratorsCount <= 1) {
            throw IllegalStateException("Cannot remove the last collaborator from budget $budgetId")
        }

        userBudgetRepository.deleteById(userBudgetId)
    }

    /**
     * The check every route under `/api/budgets/{budgetId}` makes first: 404 when the budget does not exist,
     * then 403 when it does but the user is not a collaborator.
     *
     * Existence comes first so that a budget deleted by a collaborator answers 404 and not 403 (its
     * `user_budgets` rows go with it, so the access check alone can never tell the two apart). This lets a
     * caller without access learn whether a numeric id exists, and nothing more.
     */
    @Transactional(readOnly = true)
    fun verifyBudgetAccess(budgetId: Long, userEmail: String) {
        if (!budgetRepository.existsById(budgetId)) {
            throw budgetNotFound(budgetId)
        }
        verifyUserHasAccessToBudget(budgetId, userEmail)
    }

    /** Access only; routes call [verifyBudgetAccess], which checks that the budget exists first. */
    @Transactional(readOnly = true)
    fun verifyUserHasAccessToBudget(budgetId: Long, userEmail: String) {
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = userEmail)
        if (!userBudgetRepository.existsById(userBudgetId)) {
            throw ForbiddenAccessException("You don't have access to budget with id: $budgetId")
        }
    }

    /** [verifyBudgetAccess] for callers that need the budget itself. */
    private fun findAccessibleBudget(budgetId: Long, userEmail: String): Budget {
        val budget = budgetRepository.findById(budgetId).orElseThrow { budgetNotFound(budgetId) }
        verifyUserHasAccessToBudget(budgetId, userEmail)
        return budget
    }

    /** An entry that is in another budget is reported as missing: from this budget's path it does not exist. */
    private fun findEntryInBudget(entryId: Long, budgetId: Long): BudgetEntry {
        val entry = budgetEntryRepository.findById(entryId)
            .orElseThrow { ResourceNotFoundException("Budget entry not found with id: $entryId") }
        if (entry.budget.id != budgetId) {
            throw ResourceNotFoundException("Budget entry $entryId does not belong to budget $budgetId")
        }
        return entry
    }

    /**
     * The account the token was issued to. If it no longer exists the token is no good: 401, as in the
     * user and billing services, so the client signs in again (a 404 would read as "the budget/entry is gone").
     */
    private fun authenticatedUser(email: String): User =
        userRepository.findById(email).orElseThrow { BadCredentialsException("User not found") }

    private fun budgetNotFound(budgetId: Long) = ResourceNotFoundException("Budget not found with id: $budgetId")

    private fun broadcastBudgetEntryEvent(budgetEntry: BudgetEntry, user: User, action: BudgetEntryAction) {
        val event = BudgetEntryEvent(
            budgetId = budgetEntry.budget.id!!,
            entryId = budgetEntry.id!!,
            action = action,
            userInfo = UserEventInfo(
                email = user.email,
                name = user.name
            )
        )

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
