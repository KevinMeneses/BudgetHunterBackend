package com.budgethunter.billing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Instant

class RevenueCatClientTest {

    private val builder = RestClient.builder().baseUrl("https://api.revenuecat.test")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = RevenueCatClient(builder.build(), "sk_test", "premium")

    private fun answer(json: String) {
        server.expect(requestTo("https://api.revenuecat.test/v1/subscribers/abc-123"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer sk_test"))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON))
    }

    private fun subscriber(entitlements: String) = """{"subscriber": {"entitlements": $entitlements}}"""

    @Test
    fun `an active entitlement comes back with its expiry`() {
        answer(subscriber("""{"premium": {"expires_date": "2026-12-07T10:00:00Z", "product_identifier": "monthly"}}"""))

        assertEquals(Instant.parse("2026-12-07T10:00:00Z"), client.premiumEntitlement("abc-123")?.expiresAt)
        server.verify()
    }

    @Test
    fun `a later grace period extends the expiry`() {
        answer(
            subscriber(
                """{"premium": {"expires_date": "2026-12-07T10:00:00Z",
                "grace_period_expires_date": "2026-12-14T10:00:00Z"}}"""
            )
        )

        assertEquals(Instant.parse("2026-12-14T10:00:00Z"), client.premiumEntitlement("abc-123")?.expiresAt)
    }

    @Test
    fun `no expiry means it does not run out`() {
        answer(subscriber("""{"premium": {"expires_date": null}}"""))

        val entitlement = checkNotNull(client.premiumEntitlement("abc-123")) { "expected an entitlement" }
        assertNull(entitlement.expiresAt)
    }

    @Test
    fun `a customer without the entitlement has none`() {
        answer(subscriber("""{"other": {"expires_date": "2030-01-01T00:00:00Z"}}"""))

        assertNull(client.premiumEntitlement("abc-123"))
    }

    @Test
    fun `an error from RevenueCat is reported as unavailable`() {
        server.expect(requestTo("https://api.revenuecat.test/v1/subscribers/abc-123"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertThrows<BillingUnavailableException> { client.premiumEntitlement("abc-123") }
    }

    @Test
    fun `an answer without a subscriber or with a bad date is unavailable`() {
        answer("""{"message": "odd"}""")
        assertThrows<BillingUnavailableException> { client.premiumEntitlement("abc-123") }

        server.reset()
        answer(subscriber("""{"premium": {"expires_date": "next tuesday"}}"""))
        assertThrows<BillingUnavailableException> { client.premiumEntitlement("abc-123") }
    }

    @Test
    fun `without a secret key nothing is sent`() {
        val unconfigured = RevenueCatClient(builder.build(), "", "premium")

        assertThrows<BillingUnavailableException> { unconfigured.premiumEntitlement("abc-123") }
        server.verify()
    }

    @Test
    fun `the webhook secret must match exactly`() {
        val authorization = WebhookAuthorization("s3cret")
        assertTrue(authorization.matches("s3cret"))
        assertFalse(authorization.matches("s3cret "))
        assertFalse(authorization.matches(null))
        assertFalse(WebhookAuthorization("").matches(""))
    }
}
