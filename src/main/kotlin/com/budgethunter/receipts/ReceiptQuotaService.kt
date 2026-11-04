package com.budgethunter.receipts

import com.budgethunter.dto.AiUsageResponse
import com.budgethunter.model.AiUsageId
import com.budgethunter.model.Plan
import com.budgethunter.repository.AiUsageRepository
import com.budgethunter.repository.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneOffset

/** The monthly receipt scans each plan includes. */
data class ReceiptAllowance(val freeMonthlyScans: Int, val premiumMonthlyScans: Int) {
    init {
        require(freeMonthlyScans >= 0) { "freeMonthlyScans must not be negative" }
        require(premiumMonthlyScans >= 0) { "premiumMonthlyScans must not be negative" }
    }

    fun forPlan(plan: Plan) = when (plan) {
        Plan.FREE -> freeMonthlyScans
        Plan.PREMIUM -> premiumMonthlyScans
    }
}

/** Thrown when the account has no receipt scans left this month. Answered with HTTP 402. */
class AiQuotaExceededException(val usage: AiUsageResponse) :
    RuntimeException("No AI receipt scans left this month")

/**
 * Counts receipt scans per account and calendar month (UTC) against what the account's plan includes.
 *
 * A scan is taken *before* the AI is asked ([reserve]) so two requests at once cannot both use the last
 * one, and given back ([release]) when the request then fails for a reason that is not the user's
 * (provider down, timeout, unreadable reply). Premium has a limit too, a generous fair-use one, so a
 * leaked token or a script cannot run up the AI bill.
 */
class ReceiptQuotaService(
    private val aiUsageRepository: AiUsageRepository,
    private val userRepository: UserRepository,
    private val allowance: ReceiptAllowance,
    private val clock: Clock = Clock.systemUTC()
) {

    /** A scan taken for one request; hand it back to [release] if the request fails on our side. */
    data class Reservation(val email: String, val period: String)

    fun usage(email: String): AiUsageResponse {
        val month = currentMonth()
        val used = aiUsageRepository.findById(AiUsageId(email, month.toString())).map { it.receiptScans }.orElse(0)
        return usageOf(planOf(email), month, used)
    }

    /**
     * Takes one scan from this month's allowance.
     *
     * @return the reservation and the usage *including* this scan
     * @throws AiQuotaExceededException when nothing is left
     */
    fun reserve(email: String): Pair<Reservation, AiUsageResponse> {
        val month = currentMonth()
        val period = month.toString()
        val plan = planOf(email)
        val limit = allowance.forPlan(plan)
        ensureRow(email, period)
        if (aiUsageRepository.tryConsumeReceiptScan(email, period, limit) == 0) {
            throw AiQuotaExceededException(usageOf(plan, month, used(email, period)))
        }
        return Reservation(email, period) to usageOf(plan, month, used(email, period))
    }

    fun release(reservation: Reservation) {
        aiUsageRepository.releaseReceiptScan(reservation.email, reservation.period)
    }

    private fun planOf(email: String): Plan = userRepository.findById(email).map { it.plan }.orElse(Plan.FREE)

    private fun used(email: String, period: String) =
        aiUsageRepository.findById(AiUsageId(email, period)).map { it.receiptScans }.orElse(0)

    /** The first request of the month creates the row; losing that race to a parallel request is fine. */
    private fun ensureRow(email: String, period: String) {
        try {
            aiUsageRepository.createIfMissing(email, period)
        } catch (_: DataIntegrityViolationException) {
            // Another request created it first.
        }
    }

    private fun currentMonth(): YearMonth = YearMonth.now(clock.withZone(ZoneOffset.UTC))

    private fun usageOf(plan: Plan, month: YearMonth, used: Int): AiUsageResponse {
        val limit = allowance.forPlan(plan)
        return AiUsageResponse(
            plan = plan.name,
            period = month.toString(),
            receiptScansUsed = used,
            receiptScansLimit = limit,
            receiptScansRemaining = (limit - used).coerceAtLeast(0),
            resetsOn = month.plusMonths(1).atDay(1).toString()
        )
    }
}
