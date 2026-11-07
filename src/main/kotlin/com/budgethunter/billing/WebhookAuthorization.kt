package com.budgethunter.billing

import java.security.MessageDigest

/**
 * The shared secret RevenueCat sends, verbatim, in the `Authorization` header of every webhook (set in the
 * RevenueCat dashboard and in `REVENUECAT_WEBHOOK_AUTHORIZATION`). Blank turns the webhook off: every call is
 * refused, so a server without the setting cannot be fed events.
 */
class WebhookAuthorization(private val expected: String) {

    val configured: Boolean get() = expected.isNotBlank()

    fun matches(header: String?): Boolean =
        configured && header != null && MessageDigest.isEqual(expected.toByteArray(), header.toByteArray())
}
