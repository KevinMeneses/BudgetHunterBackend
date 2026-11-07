package com.budgethunter.billing

import com.budgethunter.dto.BillingStatusResponse
import com.budgethunter.model.Plan
import com.budgethunter.model.User
import com.budgethunter.repository.UserRepository
import com.budgethunter.util.PerAccountRateLimiter
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.BadCredentialsException
import java.time.Clock

/**
 * Keeps `users.plan` in step with the store purchases.
 *
 * Whatever triggers it (a RevenueCat webhook, or the app right after a purchase or a restore), it asks
 * RevenueCat for the account's current entitlement and writes that. It never applies what an event says, so
 * duplicated, late or out-of-order events are harmless, and so is a forged one that got past the header check.
 */
class BillingService(
    private val userRepository: UserRepository,
    private val subscriberLookup: SubscriberLookup,
    private val syncLimiter: PerAccountRateLimiter = PerAccountRateLimiter(DEFAULT_SYNCS_PER_MINUTE),
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(BillingService::class.java)

    fun status(email: String): BillingStatusResponse = statusOf(userOf(email))

    /**
     * Asks RevenueCat now instead of waiting for the webhook (the app calls it after a purchase or restore).
     * Limited per account: every call costs a request on the shared secret key, which the webhook needs too.
     *
     * @throws BillingSyncThrottledException past a few calls a minute
     */
    fun sync(email: String): BillingStatusResponse {
        if (!syncLimiter.tryAcquire(email)) throw BillingSyncThrottledException()
        val user = userOf(email)
        refresh(user)
        return statusOf(userOf(email))
    }

    /**
     * Handles one webhook body. Refreshes every known account the event names (the buyer, its aliases, and
     * both sides of a TRANSFER); ids that are not ours, such as anonymous RevenueCat ids, are skipped.
     *
     * @return how many accounts were refreshed
     */
    fun onWebhook(body: JsonNode): Int {
        // If RevenueCat fails for one account the event answers 503 and RevenueCat retries it. The accounts
        // refreshed before the failure stay written; refreshing them again on the retry is harmless.
        val event = body.path("event")
        val type = event.path("type").asText("")
        val users = userRepository.findByBillingIdIn(appUserIdsIn(event))
        users.forEach(::refresh)
        log.info("RevenueCat webhook {} ({}): {} account(s) refreshed", type, event.path("id").asText(""), users.size)
        return users.size
    }

    private fun refresh(user: User) {
        val entitlement = subscriberLookup.premiumEntitlement(user.billingId)
        val expiresAt = entitlement?.expiresAt
        val now = clock.instant()
        val plan = if (entitlement != null) Plan.inForce(Plan.PREMIUM, expiresAt, now) else Plan.FREE
        userRepository.updatePlan(user.email, plan, expiresAt)
        if (plan != user.planAt(now)) log.info("Plan changed to {} for billing id {}", plan, user.billingId)
    }

    private fun userOf(email: String): User = userRepository.findById(email)
        // Deleted after its token was checked: answer like any request without a valid account (401).
        .orElseThrow { BadCredentialsException("Account not found") }

    private fun statusOf(user: User): BillingStatusResponse {
        val plan = user.planAt(clock.instant())
        return BillingStatusResponse(
            appUserId = user.billingId,
            plan = plan.name,
            premiumUntil = user.planExpiresAt?.takeIf { plan == Plan.PREMIUM }?.toString()
        )
    }

    private fun appUserIdsIn(event: JsonNode): Set<String> {
        val ids = linkedSetOf<String>()
        ID_FIELDS.forEach { field -> textIn(event.path(field)).let { if (it != null) ids += it } }
        LIST_FIELDS.forEach { field -> event.path(field).forEach { node -> textIn(node)?.let { ids += it } } }
        return ids.take(MAX_IDS_PER_EVENT).toSet()
    }

    private fun textIn(node: JsonNode): String? = node.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

    private companion object {
        val ID_FIELDS = listOf("app_user_id", "original_app_user_id")
        val LIST_FIELDS = listOf("aliases", "transferred_from", "transferred_to")

        // An event names one customer and a few aliases (in practice 1-3 of them ours). Refreshes run one after
        // another and RevenueCat waits for the answer: at most 5 ids x BillingConfig's 5 s timeout = 25 s.
        const val MAX_IDS_PER_EVENT = 5

        // Enough for purchase, restore and a retry or two.
        const val DEFAULT_SYNCS_PER_MINUTE = 5
    }
}
