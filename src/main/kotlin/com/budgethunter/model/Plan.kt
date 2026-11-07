package com.budgethunter.model

import java.time.Instant

/**
 * What an account has paid for. Everyone starts on [FREE]; [PREMIUM] is granted by the store
 * purchase (RevenueCat) and taken back when it lapses. Stored by name in `users.plan`.
 */
enum class Plan {
    FREE,
    PREMIUM;

    companion object {
        /** The one rule for "is it paid right now": PREMIUM until [expiresAt] (null = does not run out). */
        fun inForce(plan: Plan, expiresAt: Instant?, now: Instant): Plan =
            if (plan == PREMIUM && (expiresAt == null || now.isBefore(expiresAt))) PREMIUM else FREE
    }
}
