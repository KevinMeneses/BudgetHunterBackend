package com.budgethunter.integration

import com.budgethunter.billing.BillingUnavailableException
import com.budgethunter.billing.StoreEntitlement
import com.budgethunter.billing.SubscriberLookup
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.model.Plan
import com.budgethunter.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val SECRET = "webhook-secret-for-tests"

/**
 * Store purchases through the whole stack, with a fake RevenueCat: the webhook's secret, which accounts an
 * event refreshes, and how the plan then reaches the receipt allowance.
 */
@SpringBootTest(
    properties = [
        "billing.revenuecat.webhook-authorization=$SECRET",
        "receipts.free-monthly-scans=2",
        "receipts.premium-monthly-scans=3"
    ]
)
@AutoConfigureMockMvc
class BillingIntegrationTest {

    @TestConfiguration
    class FakeRevenueCat {
        @Bean
        @Primary
        fun fakeSubscriberLookup() = SubscriberLookup { appUserId ->
            asked += appUserId
            if (down) throw BillingUnavailableException("down")
            entitlements[appUserId]
        }
    }

    companion object {
        val entitlements = ConcurrentHashMap<String, StoreEntitlement>()
        val asked: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        @Volatile var down = false
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var userRepository: UserRepository

    private val email = "billing-${UUID.randomUUID().toString().take(8)}@example.com"
    private lateinit var token: String
    private lateinit var billingId: String

    @BeforeEach
    fun setup() {
        down = false
        asked.clear()
        token = signUp(email)
        billingId = userRepository.findById(email).get().billingId
    }

    private fun signUp(address: String): String {
        mockMvc.perform(
            post("/api/users/sign_up").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignUpRequest(address, "User", "Password123!")))
        )
        val result = mockMvc.perform(
            post("/api/users/sign_in").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignInRequest(address, "Password123!")))
        ).andReturn()
        return objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java).authToken
    }

    private fun webhook(event: Map<String, Any?>, authorization: String? = SECRET): ResultActions {
        val request = post("/api/webhooks/revenuecat").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("api_version" to "1.0", "event" to event)))
        authorization?.let { request.header("Authorization", it) }
        return mockMvc.perform(request)
    }

    private fun purchase(appUserId: String = billingId) =
        mapOf("type" to "INITIAL_PURCHASE", "id" to UUID.randomUUID().toString(), "app_user_id" to appUserId)

    private fun inAMonth() = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)

    private fun billingStatus(): ResultActions =
        mockMvc.perform(get("/api/billing/status").header("Authorization", "Bearer $token"))

    @Test
    fun `a new account is FREE and has its own random RevenueCat id`() {
        val other = "billing-other-${UUID.randomUUID().toString().take(8)}@example.com"
        signUp(other)

        billingStatus().andExpect(status().isOk)
            .andExpect(jsonPath("$.appUserId").value(billingId))
            .andExpect(jsonPath("$.plan").value("FREE"))
            .andExpect(jsonPath("$.premiumUntil").doesNotExist())
        assertEquals(36, billingId.length)
        assertNotEquals(billingId, userRepository.findById(other).get().billingId)
    }

    @Test
    fun `the webhook refuses a missing or wrong secret and changes nothing`() {
        entitlements[billingId] = StoreEntitlement(inAMonth())

        webhook(purchase(), authorization = null).andExpect(status().isUnauthorized)
        webhook(purchase(), authorization = "Bearer $token").andExpect(status().isUnauthorized)

        assertEquals(Plan.FREE, userRepository.findById(email).get().plan)
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `a purchase makes the account PREMIUM with what RevenueCat says, and raises the allowance`() {
        val until = inAMonth()
        entitlements[billingId] = StoreEntitlement(until)

        // The event itself carries nothing the server trusts: the plan comes from the lookup.
        webhook(purchase() + ("entitlement_ids" to listOf("something-else"))).andExpect(status().isOk)
            .andExpect(jsonPath("$.accountsRefreshed").value(1))

        billingStatus().andExpect(jsonPath("$.plan").value("PREMIUM"))
            .andExpect(jsonPath("$.premiumUntil").value(until.toString()))
        mockMvc.perform(get("/api/receipts/usage").header("Authorization", "Bearer $token"))
            .andExpect(jsonPath("$.plan").value("PREMIUM"))
            .andExpect(jsonPath("$.receiptScansLimit").value(3))
    }

    @Test
    fun `an expiration event makes the account FREE again`() {
        entitlements[billingId] = StoreEntitlement(inAMonth())
        webhook(purchase())
        entitlements[billingId] = StoreEntitlement(Instant.now().minus(1, ChronoUnit.HOURS))

        webhook(mapOf("type" to "EXPIRATION", "app_user_id" to billingId)).andExpect(status().isOk)

        billingStatus().andExpect(jsonPath("$.plan").value("FREE"))
            .andExpect(jsonPath("$.premiumUntil").doesNotExist())
    }

    @Test
    fun `PREMIUM past its expiry counts as FREE even without the expiration webhook`() {
        val user = userRepository.findById(email).get()
        userRepository.updatePlan(user.email, Plan.PREMIUM, Instant.now().minusSeconds(60))

        billingStatus().andExpect(jsonPath("$.plan").value("FREE"))
        mockMvc.perform(get("/api/receipts/usage").header("Authorization", "Bearer $token"))
            .andExpect(jsonPath("$.receiptScansLimit").value(2))
    }

    @Test
    fun `aliases and both sides of a transfer are refreshed, unknown ids are skipped`() {
        val otherEmail = "billing-to-${UUID.randomUUID().toString().take(8)}@example.com"
        signUp(otherEmail)
        val otherId = userRepository.findById(otherEmail).get().billingId
        entitlements[otherId] = StoreEntitlement(null)

        webhook(
            mapOf(
                "type" to "TRANSFER",
                "transferred_from" to listOf(billingId, "\$RCAnonymousID:123"),
                "transferred_to" to listOf(otherId)
            )
        ).andExpect(status().isOk).andExpect(jsonPath("$.accountsRefreshed").value(2))

        assertEquals(Plan.FREE, userRepository.findById(email).get().plan)
        val other = userRepository.findById(otherEmail).get()
        assertEquals(Plan.PREMIUM, other.plan)
        assertEquals(null, other.planExpiresAt)
    }

    @Test
    fun `a test event or an unknown customer is acknowledged without changes`() {
        webhook(mapOf("type" to "TEST", "app_user_id" to "not-ours")).andExpect(status().isOk)
            .andExpect(jsonPath("$.accountsRefreshed").value(0))
    }

    @Test
    fun `when RevenueCat cannot be asked the webhook answers 503 so it is retried`() {
        down = true

        webhook(purchase()).andExpect(status().isServiceUnavailable)
        mockMvc.perform(post("/api/billing/sync").header("Authorization", "Bearer $token"))
            .andExpect(status().isServiceUnavailable)
    }

    @Test
    fun `sync applies a purchase without waiting for the webhook`() {
        entitlements[billingId] = StoreEntitlement(inAMonth())

        mockMvc.perform(post("/api/billing/sync").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.plan").value("PREMIUM"))
        assertEquals(listOf(billingId), asked)
    }

    @Test
    fun `sync is limited per account`() {
        repeat(5) {
            mockMvc.perform(post("/api/billing/sync").header("Authorization", "Bearer $token")).andExpect(status().isOk)
        }
        mockMvc.perform(post("/api/billing/sync").header("Authorization", "Bearer $token"))
            .andExpect(status().isTooManyRequests)
    }

    @Test
    fun `a body that is not an event is acknowledged without changes, and junk is a 400`() {
        for (body in listOf("[]", "{}", "{\"event\": 3}")) {
            mockMvc.perform(
                post("/api/webhooks/revenuecat").contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", SECRET).content(body)
            ).andExpect(status().isOk).andExpect(jsonPath("$.accountsRefreshed").value(0))
        }
        mockMvc.perform(
            post("/api/webhooks/revenuecat").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", SECRET).content("{not json")
        ).andExpect(status().isBadRequest)
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `an event naming many ids refreshes only the first few`() {
        val aliases = (1..20).map { "alias-$it" }

        webhook(mapOf("type" to "RENEWAL", "app_user_id" to billingId, "aliases" to aliases))
            .andExpect(status().isOk).andExpect(jsonPath("$.accountsRefreshed").value(1))
        // Only the first ten ids are looked up, and the buyer's own id comes first.
        assertEquals(listOf(billingId), asked)
    }

    @Test
    fun `the billing endpoints need a signed-in account`() {
        mockMvc.perform(get("/api/billing/status")).andExpect(status().isUnauthorized)
        mockMvc.perform(post("/api/billing/sync")).andExpect(status().isUnauthorized)
    }
}
