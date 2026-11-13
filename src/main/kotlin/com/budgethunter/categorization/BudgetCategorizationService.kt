package com.budgethunter.categorization

import com.budgethunter.dto.CategorizeEntriesResponse
import com.budgethunter.exception.ForbiddenAccessException
import com.budgethunter.model.EntryCategory
import com.budgethunter.repository.BudgetEntryRepository
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
 * Only entries that are `UNCATEGORIZED` are looked at: nobody has chosen a category for them. That keeps a
 * second run from redoing the first (an entry the AI placed, even in OTHER, now has a category) and keeps it
 * away from everything a person categorised. No other marker is needed.
 *
 * What is sent to the AI is charged to the caller's monthly allowance ([CategorizationQuotaService]), never to
 * the entries' creators. Past the allowance the run still applies the rules and the cache, sends only what is
 * left of it, and leaves the rest waiting with `quotaExceeded` set; it does not fail. Without [quota] nothing
 * is counted (tests).
 */
class BudgetCategorizationService(
    private val resolver: CategoryResolver,
    private val budgetService: BudgetService,
    private val budgetEntryRepository: BudgetEntryRepository,
    private val userRepository: UserRepository,
    private val metrics: CategorizationMetrics = CategorizationMetrics(),
    private val quota: CategorizationQuotaService? = null
) {

    private val log = LoggerFactory.getLogger(BudgetCategorizationService::class.java)

    // Budgets being categorised right now. A second tap, or a collaborator's, would only spend the quota twice.
    private val running: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun categorize(budgetId: Long, userEmail: String): CategorizeEntriesResponse {
        // 404 for a missing budget, then 403 for no access, as on every budget route. Existence must come first:
        // a deleted budget takes its user_budgets rows with it, so access-first answered 403, which the app reads
        // as "AI processing is off". The app shows its generic "not found" message for a 404.
        budgetService.verifyBudgetAccess(budgetId, userEmail)
        if (!userAllowsAi(userEmail)) {
            metrics.run(CategorizationMetrics.Run.AI_OFF)
            throw ForbiddenAccessException("AI processing is turned off for this account")
        }
        if (!running.add(budgetId)) {
            metrics.run(CategorizationMetrics.Run.ALREADY_RUNNING)
            error("Categorization is already running for budget $budgetId")
        }
        try {
            return run(budgetId, userEmail).also { metrics.run(CategorizationMetrics.Run.COMPLETED) }
        } finally {
            running.remove(budgetId)
        }
    }

    private fun run(budgetId: Long, userEmail: String): CategorizeEntriesResponse {
        val waiting = budgetEntryRepository.findPendingCategorization(
            budgetId,
            EntryCategory.UNCATEGORIZED,
            PageRequest.of(0, MAX_ENTRIES_PER_RUN)
        )
        val resolution = resolver.resolve(
            waiting.map { it.description },
            quota?.budgetFor(userEmail) ?: RemoteBudget.UNLIMITED
        )
        val answers = resolution.answers

        // OTHER is an answer too ("looked at it, none fits"): storing it settles the entry.
        val stored = waiting.indices.map { index -> answers[index]?.let { store(waiting[index], it) } }
        val categorized = stored.count { it == true }
        val usage = quota?.usage(userEmail)
        // Held back only counts as the allowance running out if it is still used up once what the AI did not
        // answer was given back: after a provider failure those entries wait for the provider, not for Premium.
        val heldBack = if (usage?.categorizedEntriesRemaining == 0) resolution.heldBack.size else 0
        metrics.entries(CategorizationMetrics.EntryResult.CATEGORIZED, categorized)
        metrics.entries(CategorizationMetrics.EntryResult.DROPPED, stored.count { it == false })
        metrics.entries(CategorizationMetrics.EntryResult.UNPLACED, stored.count { it == null } - heldBack)
        metrics.entries(CategorizationMetrics.EntryResult.QUOTA_EXCEEDED, heldBack)
        log.info("Categorized {} of {} waiting entries of budget {}", categorized, waiting.size, budgetId)
        if (heldBack > 0) {
            log.info("Monthly AI categorizations used up; {} entries of budget {} left waiting", heldBack, budgetId)
        }

        return CategorizeEntriesResponse(
            categorized = categorized,
            pending = budgetEntryRepository.countPendingCategorization(budgetId, EntryCategory.UNCATEGORIZED),
            quotaExceeded = heldBack > 0,
            usage = usage
        )
    }

    private fun store(entry: PendingCategorization, category: String): Boolean =
        budgetEntryRepository.applyAutoCategory(
            entry.id,
            entry.description,
            category,
            EntryCategory.UNCATEGORIZED,
            LocalDateTime.now()
        ) > 0

    private fun userAllowsAi(email: String): Boolean =
        userRepository.findById(email).map { it.aiProcessingEnabled == true }.orElse(false)

    private companion object {
        // Bounds memory and work per request. Rules and the cache answer most instantly; Gemini's own
        // per-minute budget bounds what can be asked remotely, so a bigger backlog just takes a few runs.
        // Entries the classifier placed leave the waiting set, so only ones it could not be asked about
        // (no key, quota) can pile up at the front.
        const val MAX_ENTRIES_PER_RUN = 500
    }
}
