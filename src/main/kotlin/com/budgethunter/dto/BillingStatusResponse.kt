package com.budgethunter.dto

/** The signed-in account's plan, and the id the app logs in to RevenueCat with. */
data class BillingStatusResponse(
    /** RevenueCat's "app user id" for this account. The app passes it to `Purchases.logIn` after sign-in. */
    val appUserId: String,
    /** FREE or PREMIUM, as in force right now. */
    val plan: String,
    /** End of the paid period (ISO-8601, UTC), or null when FREE or when it does not run out. */
    val premiumUntil: String?,
    /**
     * What the account can share as a budget owner (null limits = unlimited). Null only where the server does not
     * fill it in; `GET /status` and `POST /sync` always do.
     */
    val sharing: SharingStatusResponse? = null
)
