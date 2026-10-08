package com.budgethunter.categorization

import com.budgethunter.dto.CategorizeEntriesResponse
import com.budgethunter.exception.ForbiddenAccessException
import com.budgethunter.model.EntryCategory
import com.budgethunter.repository.BudgetEntryRepository
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.PendingCategorization
import com.budgethunter.repository.UserRepository
import com.budgethunter.service.BudgetService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives a budget's waiting entries an automatic category, when the user asks for it.
 *
 * Runs inside the request, not in the background: the user asked and is waiting for the answer, so there is
 * nothing to notify afterwards. Not transactional on purpose, because asking the classifier is an HTTP call
 * and a database connection must not be held while it runs. The only writes are conditional UPDATEs that
 * match only while an entry is still automatic and still has the description it was classified from, so a
 * person's concurrent edit always wins and nothing else on the entry is touched.
 *
 * Only entries that are `AUTO` and still at the `OTHER` placeholder are looked at, which is what keeps a
 * second run from redoing the first one and what keeps it away from everything a person categorised.
 */
class BudgetCategorizationService(
    private val resolver: CategoryResolver,
    private val budgetService: BudgetService,
    private val budgetRepository: BudgetRepository,
    private val budgetEntryRepository: BudgetEntryRepository,
    private val userRepository: UserRepository
) {

    private val log = LoggerFactory.getLogger(BudgetCategorizationService::class.java)

    // Budgets being categorised right now. A second tap, or a collaborator's, would only spend the quota twice.
    private val running: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun categorize(budgetId: Long, userEmail: String): CategorizeEntriesResponse {
        budgetService.verifyUserHasAccessToBudget(budgetId, userEmail)
        require(budgetRepository.existsById(budgetId)) { "Budget not found with id: $budgetId" }
        if (!userAllowsAi(userEmail)) {
            throw ForbiddenAccessException("AI processing is turned off for this account")
        }
        check(running.add(budgetId)) { "Categorization is already running for budget $budgetId" }
        try {
            return run(budgetId)
        } finally {
            running.remove(budgetId)
        }
    }

    private fun run(budgetId: Long): CategorizeEntriesResponse {
        val waiting = budgetEntryRepository.findPendingCategorization(
            budgetId,
            EntryCategory.OTHER,
            PageRequest.of(0, MAX_ENTRIES_PER_RUN)
        )
        val answers = resolver.resolve(waiting.map { it.description })

        val categorized = waiting.indices.count { index ->
            val category = answers[index]
            // OTHER is what is already stored: writing it would only bump the modification date.
            category != null && category != EntryCategory.OTHER && store(waiting[index], category)
        }
        log.info("Categorized {} of {} waiting entries of budget {}", categorized, waiting.size, budgetId)

        return CategorizeEntriesResponse(
            categorized = categorized,
            pending = budgetEntryRepository.countPendingCategorization(budgetId, EntryCategory.OTHER)
        )
    }

    private fun store(entry: PendingCategorization, category: String): Boolean =
        budgetEntryRepository.applyAutoCategory(entry.id, entry.description, category, LocalDateTime.now()) > 0

    private fun userAllowsAi(email: String): Boolean =
        userRepository.findById(email).map { it.aiProcessingEnabled == true }.orElse(false)

    private companion object {
        // Bounds memory and work per request. Rules and the cache answer most instantly; Gemini's own
        // per-minute budget bounds what can be asked remotely, so a bigger backlog just takes a few runs.
        const val MAX_ENTRIES_PER_RUN = 500
    }
}
