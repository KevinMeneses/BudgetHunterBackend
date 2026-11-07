package com.budgethunter.billing

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Reads a customer from RevenueCat's REST API (`GET /v1/subscribers/{app_user_id}`) with the project's secret
 * key. The server never trusts what a webhook says the plan is: it asks here, so events that arrive late, out
 * of order or twice all end in the current state.
 *
 * Note that RevenueCat creates the customer if it does not exist yet; harmless, the app does the same on login.
 */
class RevenueCatClient(
    private val restClient: RestClient,
    private val secretApiKey: String,
    private val entitlementId: String
) : SubscriberLookup {

    override fun premiumEntitlement(appUserId: String): StoreEntitlement? {
        if (secretApiKey.isBlank()) throw BillingUnavailableException("REVENUECAT_SECRET_API_KEY is not set")
        val body = try {
            restClient.get()
                .uri("/v1/subscribers/{id}", appUserId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $secretApiKey")
                .retrieve()
                .body(JsonNode::class.java)
        } catch (e: RestClientException) {
            throw BillingUnavailableException("RevenueCat did not answer: ${e.javaClass.simpleName}", e)
        } ?: throw BillingUnavailableException("RevenueCat answered with an empty body")
        return entitlementIn(body)
    }

    private fun entitlementIn(body: JsonNode): StoreEntitlement? {
        val subscriber = body.path("subscriber")
        if (!subscriber.isObject) throw BillingUnavailableException("RevenueCat answered without a subscriber")
        val entitlement = subscriber.path("entitlements").path(entitlementId)
        return if (entitlement.isObject) StoreEntitlement(expiresAt = expiryOf(entitlement)) else null
    }

    /** Null when it does not run out. While the store retries a failed charge, it holds until the grace ends. */
    private fun expiryOf(entitlement: JsonNode): Instant? {
        val expires = dateIn(entitlement, "expires_date")
        val grace = dateIn(entitlement, "grace_period_expires_date")
        return if (expires != null && grace != null && grace.isAfter(expires)) grace else expires
    }

    private fun dateIn(node: JsonNode, field: String): Instant? {
        val value = node.path(field)
        if (!value.isTextual) return null
        return try {
            Instant.parse(value.asText())
        } catch (e: DateTimeParseException) {
            throw BillingUnavailableException("RevenueCat sent an unreadable $field", e)
        }
    }
}
