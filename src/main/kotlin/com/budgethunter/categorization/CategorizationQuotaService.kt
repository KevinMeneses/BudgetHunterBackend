package com.budgethunter.categorization

import com.budgethunter.dto.CategorizationUsageResponse
import com.budgethunter.model.AiUsageId
import com.budgethunter.model.Plan
import com.budgethunter.repository.AiUsageRepository
import com.budgethunter.repository.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneOffset

/** The descriptions each plan may send to the AI for automatic categorization per month. */
data class CategorizationAllowance(val freeMonthlyEntries: Int, val premiumMonthlyEntries: Int) {
    init {
        require(freeMonthlyEntries >= 0) { "freeMonthlyEntries must not be negative" }
        require(premiumMonthlyEntries >= 0) { "premiumMonthlyEntries must not be negative" }
    }

    fun forPlan(plan: Plan) = when (plan) {
        Plan.FREE -> freeMonthlyEntries
        Plan.PREMIUM -> premiumMonthlyEntries
    }
}

/**
 * Counts what automatic categorization sends to the AI, per account and calendar month (UTC), against what the
 * account's plan includes; the receipt scans' twin ([com.budgethunter.receipts.ReceiptQuotaService]), in the same
 * `ai_usage` row.
 *
 * The unit is a distinct description sent to Gemini: several entries with the same description are one question,
 * and what the rules or the cache answer is free. The account charged is the one that asked for the run, whoever
 * created the entries.
 *
 * Unlike a scan, a run takes many units at once and takes only as many as are left ([reserve]), so a run past
 * the limit still sends what fits instead of failing. Units are taken *before* the AI is asked, so two runs at
 * once cannot both use the last ones, and given back ([release]) for descriptions the AI then did not answer
 * (provider error, timeout, its own quota). An answer of OTHER is an answer and counts.
 */
class CategorizationQuotaService(
    private val aiUsageRepository: AiUsageRepository,
    private val userRepository: UserRepository,
    private val allowance: CategorizationAllowance,
    private val clock: Clock = Clock.systemUTC()
) {

    /** Units taken for one run, in the month they were taken from. */
    data class Reservation(val email: String, val period: String, val granted: Int)

    fun usage(email: String): CategorizationUsageResponse {
        val month = currentMonth()
        return usageOf(planOf(email), month, used(email, month.toString()))
    }

    /** A [RemoteBudget] that charges [email]. Gives back to the month it took from, even across a month's end. */
    fun budgetFor(email: String): RemoteBudget = object : RemoteBudget {
        private var period: String? = null

        override fun take(wanted: Int): Int = reserve(email, wanted).also { period = it.period }.granted

        override fun giveBack(unused: Int) {
            period?.let { release(Reservation(email, it, unused)) }
        }
    }

    /**
     * Takes up to [wanted] units from this month's allowance: all of them if that many are left, otherwise what
     * is left (possibly none). Each attempt is one conditional UPDATE that can never pass the limit; one that
     * loses a race to another run reads what is left and asks for that instead.
     */
    fun reserve(email: String, wanted: Int): Reservation {
        val period = currentMonth().toString()
        val limit = allowance.forPlan(planOf(email))
        var amount = wanted.coerceAtMost(limit)
        var granted = 0
        var attempts = 0
        if (amount > 0) ensureRow(email, period)
        while (granted == 0 && amount > 0 && attempts < MAX_ATTEMPTS) {
            attempts++
            if (aiUsageRepository.tryConsumeCategorizations(email, period, amount, limit) > 0) {
                granted = amount
            } else {
                amount = minOf(amount, limit - used(email, period))
            }
        }
        return Reservation(email, period, granted)
    }

    fun release(reservation: Reservation) {
        if (reservation.granted > 0) {
            aiUsageRepository.releaseCategorizations(reservation.email, reservation.period, reservation.granted)
        }
    }

    private fun planOf(email: String): Plan =
        userRepository.findById(email).map { it.planAt(clock.instant()) }.orElse(Plan.FREE)

    private fun used(email: String, period: String) =
        aiUsageRepository.findById(AiUsageId(email, period)).map { it.categorizedEntries }.orElse(0)

    /** The first use of the month creates the row; losing that race to a parallel request is fine. */
    private fun ensureRow(email: String, period: String) {
        try {
            aiUsageRepository.createIfMissing(email, period)
        } catch (_: DataIntegrityViolationException) {
            // Another request created it first.
        }
    }

    private fun currentMonth(): YearMonth = YearMonth.now(clock.withZone(ZoneOffset.UTC))

    private fun usageOf(plan: Plan, month: YearMonth, used: Int): CategorizationUsageResponse {
        val limit = allowance.forPlan(plan)
        return CategorizationUsageResponse(
            plan = plan.name,
            period = month.toString(),
            categorizedEntriesUsed = used,
            categorizedEntriesLimit = limit,
            categorizedEntriesRemaining = (limit - used).coerceAtLeast(0),
            resetsOn = month.plusMonths(1).atDay(1).toString()
        )
    }

    private companion object {
        // Each lost attempt means another run of the same account took units in between, so this only runs out
        // under a burst no person produces; the run then sends nothing to the AI and reports the quota as reached.
        const val MAX_ATTEMPTS = 5
    }
}
