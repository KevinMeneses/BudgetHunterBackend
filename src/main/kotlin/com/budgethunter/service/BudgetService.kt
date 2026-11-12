package com.budgethunter.service

import com.budgethunter.dto.AddCollaboratorRequest
import com.budgethunter.dto.BudgetEntryAction
import com.budgethunter.dto.BudgetEntryEvent
import com.budgethunter.dto.BudgetEntryResponse
import com.budgethunter.dto.BudgetMemberResponse
import com.budgethunter.dto.BudgetResponse
import com.budgethunter.dto.CollaboratorResponse
import com.budgethunter.dto.CreateBudgetEntryRequest
import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.PageResponse
import com.budgethunter.dto.PutEntryRequest
import com.budgethunter.dto.UpdateBudgetEntryRequest
import com.budgethunter.dto.UpdateBudgetRequest
import com.budgethunter.dto.UserEventInfo
import com.budgethunter.exception.ForbiddenAccessException
import com.budgethunter.model.Budget
import com.budgethunter.model.BudgetEntry
import com.budgethunter.model.EntryCategory
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
        val user = userRepository.findById(userEmail)
            .orElseThrow { IllegalArgumentException("User not found") }

        val budget = Budget(
            name = request.name,
            amount = request.amount,
            date = request.date ?: LocalDate.now(),
            ownerEmail = userEmail
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
            collaboratorCount = 1,
            ownerEmail = savedBudget.ownerEmail
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
        verifyUserHasAccessToBudget(budgetId, authenticatedUserEmail)

        val budget = budgetRepository.findById(budgetId)
            .orElseThrow { IllegalArgumentException("Budget not found with id: $budgetId") }

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
            collaboratorCount = userBudgetRepository.countByBudgetId(budgetId).toInt(),
            ownerEmail = savedBudget.ownerEmail
        )
    }

    /**
     * Any member may add others, not only the owner: that is how it has always worked and what the installed
     * apps offer every member, and an addition destroys nothing - the owner can remove anyone added.
     */
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
            collaboratorName = collaborator.name,
            isOwner = collaborator.email == budget.ownerEmail
        )
    }

    @Transactional(readOnly = true)
    fun getCollaboratorsByBudgetId(budgetId: Long, authenticatedUserEmail: String): List<BudgetMemberResponse> {
        verifyUserHasAccessToBudget(budgetId, authenticatedUserEmail)

        val budget = budgetRepository.findById(budgetId)
            .orElseThrow { IllegalArgumentException("Budget not found with id: $budgetId") }

        val users = userBudgetRepository.findUsersByBudgetId(budgetId)
        return users.map { user ->
            BudgetMemberResponse(
                email = user.email,
                name = user.name,
                isOwner = user.email == budget.ownerEmail
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
        verifyUserHasAccessToBudget(budgetId, authenticatedUserEmail)

        val user = userRepository.findById(authenticatedUserEmail)
            .orElseThrow { IllegalArgumentException("User not found with email: $authenticatedUserEmail") }

        val existingEntry = budgetEntryRepository.findById(entryId)
            .orElseThrow { IllegalArgumentException("Budget entry not found with id: $entryId") }

        if (existingEntry.budget.id != budgetId) {
            throw IllegalArgumentException("Budget entry $entryId does not belong to budget $budgetId")
        }

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

    private fun updateExistingEntry(request: PutEntryRequest, budget: Budget, user: com.budgethunter.model.User): BudgetEntry {
        val existingEntry = budgetEntryRepository.findById(request.id!!)
            .orElseThrow { IllegalArgumentException("Budget entry not found with id: ${request.id}") }

        if (existingEntry.budget.id != budget.id) {
            throw IllegalArgumentException("Budget entry ${request.id} does not belong to budget ${budget.id}")
        }

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
        collaboratorCount = counts[id] ?: 0,
        ownerEmail = ownerEmail
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

    /**
     * `DELETE /api/budgets/{id}`. The owner deletes the budget for everyone. Any other member only leaves it
     * (see [leaveBudget]) and gets the same 204: the installed apps call this endpoint whenever a user deletes
     * a budget, shared or not, and all they need is for it to be gone from that user's list - which it is,
     * without taking it away from everybody else.
     */
    @Transactional
    fun deleteBudget(budgetId: Long, authenticatedUserEmail: String) {
        val budget = findBudgetForMember(budgetId, authenticatedUserEmail)

        if (budget.ownerEmail != authenticatedUserEmail) {
            userBudgetRepository.deleteById(UserBudgetId(budgetId = budgetId, userEmail = authenticatedUserEmail))
            return
        }

        deleteForEveryone(budgetId)
    }

    /** The caller stops being a member. The owner cannot: they delete the budget instead (409). */
    @Transactional
    fun leaveBudget(budgetId: Long, authenticatedUserEmail: String) {
        val budget = findBudgetForMember(budgetId, authenticatedUserEmail)

        check(budget.ownerEmail != authenticatedUserEmail) {
            "The owner cannot leave budget $budgetId; delete the budget instead"
        }

        userBudgetRepository.deleteById(UserBudgetId(budgetId = budgetId, userEmail = authenticatedUserEmail))
    }

    /**
     * Hands over every budget [userEmail] owns, to be called right before their account is deleted: each one
     * goes to another member ([BudgetRepository.findSuccessorOwner]: whoever created its oldest entry, else the
     * lowest email) or, when nobody else is left in it, is deleted. The foreign key on `budgets.owner_email`
     * refuses to delete an account that still owns budgets, so skipping this fails loudly instead of leaving
     * budgets without an owner. Their memberships are left alone: deleting the account removes them.
     */
    @Transactional
    fun handOverOwnedBudgets(userEmail: String) {
        budgetRepository.findByOwnerEmail(userEmail).forEach { budget ->
            val budgetId = budget.id!!
            val successor = budgetRepository.findSuccessorOwner(budgetId, userEmail)
            if (successor == null) {
                deleteForEveryone(budgetId)
            } else {
                budgetRepository.updateOwner(budgetId, successor)
            }
        }
    }

    private fun deleteForEveryone(budgetId: Long) {
        // Entries and memberships first, for the foreign keys.
        budgetEntryRepository.deleteByBudgetId(budgetId)
        userBudgetRepository.deleteByBudgetId(budgetId)
        budgetRepository.deleteById(budgetId)
    }

    /** The budget, once the caller is known to be a member (403 otherwise). */
    private fun findBudgetForMember(budgetId: Long, userEmail: String): Budget {
        verifyUserHasAccessToBudget(budgetId, userEmail)
        return budgetRepository.findById(budgetId)
            .orElseThrow { IllegalArgumentException("Budget not found with id: $budgetId") }
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

    /**
     * Removes [collaboratorEmail] from the budget. Removing yourself is leaving ([leaveBudget]); removing
     * anyone else is for the owner only (403). Nobody can remove the owner: others are not allowed to remove
     * anyone, and the owner removing themselves is the owner leaving (409). Since the owner always stays a
     * member, a budget can no longer lose its last one.
     */
    @Transactional
    fun removeCollaborator(budgetId: Long, collaboratorEmail: String, authenticatedUserEmail: String) {
        if (collaboratorEmail == authenticatedUserEmail) {
            leaveBudget(budgetId, authenticatedUserEmail)
            return
        }

        val budget = findBudgetForMember(budgetId, authenticatedUserEmail)
        if (budget.ownerEmail != authenticatedUserEmail) {
            throw ForbiddenAccessException("Only the owner of budget $budgetId can remove its members")
        }

        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = collaboratorEmail)

        if (!userBudgetRepository.existsById(userBudgetId)) {
            throw IllegalArgumentException("User $collaboratorEmail is not a collaborator on budget $budgetId")
        }

        userBudgetRepository.deleteById(userBudgetId)
    }

    @Transactional(readOnly = true)
    fun verifyUserHasAccessToBudget(budgetId: Long, userEmail: String) {
        val userBudgetId = UserBudgetId(budgetId = budgetId, userEmail = userEmail)
        if (!userBudgetRepository.existsById(userBudgetId)) {
            throw ForbiddenAccessException("You don't have access to budget with id: $budgetId")
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
