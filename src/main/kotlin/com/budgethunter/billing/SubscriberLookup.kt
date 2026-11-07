package com.budgethunter.billing

import java.time.Instant

/** What the store side says about one account's premium entitlement. */
data class StoreEntitlement(
    /** End of the paid period, grace period included; null when it does not run out (lifetime). */
    val expiresAt: Instant?
)

/** Thrown when the purchases provider cannot be asked (not configured, down, or an unusable answer). */
class BillingUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Thrown when an account asks for a sync too often. Answered with HTTP 429. */
class BillingSyncThrottledException : RuntimeException("Too many plan refreshes; try again in a minute")

/** Asks the purchases provider (RevenueCat) for an account's entitlement. */
fun interface SubscriberLookup {
    /**
     * @param appUserId the account's `billingId`
     * @return the entitlement, or null when the account never had it
     * @throws BillingUnavailableException when the provider cannot answer
     */
    fun premiumEntitlement(appUserId: String): StoreEntitlement?
}
