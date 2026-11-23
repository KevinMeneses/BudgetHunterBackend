package com.budgethunter.integration

import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.model.Plan
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.UserRepository
import com.budgethunter.util.GoogleUserInfo
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

/**
 * The endpoints behind `/eliminar-cuenta`. The attempt limiter is a singleton shared by every test of the cached
 * context, so each test uses its own emails and its own client IP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubGoogleTokenVerifierConfig::class)
@Transactional
@ExtendWith(OutputCaptureExtension::class)
class WebAccountDeletionIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var budgetRepository: BudgetRepository

    @Autowired private lateinit var googleTokenVerifier: StubGoogleTokenVerifier

    @Autowired private lateinit var entityManager: EntityManager

    private val password = "TestPassword123!"
    private val ip = "ip-${UUID.randomUUID()}"

    @AfterEach
    fun resetGoogle() {
        googleTokenVerifier.next = null
    }

    private fun uniqueEmail(prefix: String) = "$prefix-${UUID.randomUUID()}@example.com"

    private fun signUp(email: String): SignInResponse {
        mockMvc.perform(
            post("/api/users/sign_up").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignUpRequest(email, "Test", password)))
        ).andExpect(status().isCreated)
        val body = mockMvc.perform(
            post("/api/users/sign_in").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignInRequest(email, password)))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, SignInResponse::class.java)
    }

    /** A bulk update leaves the test transaction's cached User stale; the service must read the new plan. */
    private fun makePremium(email: String) {
        userRepository.updatePlan(email, Plan.PREMIUM, null)
        entityManager.clear()
    }

    private fun deleteWithCredentials(
        email: String,
        password: String,
        confirm: Boolean = true,
        clientIp: String = ip
    ): ResultActions = mockMvc.perform(
        post("/api/users/delete_with_credentials").contentType(MediaType.APPLICATION_JSON)
            .header("X-Real-IP", clientIp)
            .content(objectMapper.writeValueAsString(mapOf("email" to email, "password" to password, "confirm" to confirm)))
    )

    private fun deleteWithGoogle(confirm: Boolean = true): ResultActions = mockMvc.perform(
        post("/api/users/delete_with_google").contentType(MediaType.APPLICATION_JSON)
            .header("X-Real-IP", ip)
            .content(objectMapper.writeValueAsString(mapOf("idToken" to "stub-id-token", "confirm" to confirm)))
    )

    private fun ResultActions.andExpectInvalidCredentials() = andExpect(status().isUnauthorized)
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
        .andExpect(jsonPath("$.message").value("Invalid email or password"))

    @Test
    fun `the page and its config are public`() {
        mockMvc.perform(get("/eliminar-cuenta")).andExpect(status().isOk)
        val page = mockMvc.perform(get("/eliminar-cuenta.html")).andExpect(status().isOk)
            .andReturn().response.contentAsByteArray.toString(Charsets.UTF_8)
        assertThat(page, containsString("BudgetHunter"))
        assertThat(page, containsString("Ajustes &gt; Eliminar cuenta"))
        assertThat(page, containsString("https://play.google.com/store/account/subscriptions"))

        // No Google client id in the tests: the page hides the button.
        mockMvc.perform(get("/api/users/delete_account/config"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.googleClientId").doesNotExist())
    }

    @Test
    fun `the right credentials delete the account and its budgets`() {
        val email = uniqueEmail("web-delete")
        val session = signUp(email)
        val budget = mockMvc.perform(
            post("/api/budgets").header("Authorization", "Bearer ${session.authToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreateBudgetRequest("Solo", BigDecimal("100.00"))))
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val budgetId = objectMapper.readTree(budget)["id"].asLong()

        deleteWithCredentials(email, password)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deleted").value(true))
            .andExpect(jsonPath("$.plan").value("FREE"))
            .andExpect(jsonPath("$.cancelSubscriptionInStore").value(false))

        assertFalse(userRepository.existsById(email))
        assertFalse(budgetRepository.existsById(budgetId), "a budget nobody else is in goes with the account")
        mockMvc.perform(get("/api/budgets").header("Authorization", "Bearer ${session.authToken}"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `a PREMIUM account is told to cancel its subscription in the store`() {
        val email = uniqueEmail("web-delete-premium")
        signUp(email)
        makePremium(email)

        deleteWithCredentials(email, password)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.plan").value("PREMIUM"))
            .andExpect(jsonPath("$.cancelSubscriptionInStore").value(true))
        assertFalse(userRepository.existsById(email))
    }

    @Test
    fun `wrong credentials answer the same error and delete nothing`() {
        val email = uniqueEmail("web-delete-wrong")
        signUp(email)
        val googleOnly = uniqueEmail("web-delete-google-only")
        googleTokenVerifier.next = GoogleUserInfo("subject-${UUID.randomUUID()}", googleOnly, true, "G")
        mockMvc.perform(
            post("/api/users/sign_in_with_google").contentType(MediaType.APPLICATION_JSON)
                .content("""{"idToken":"stub-id-token"}""")
        ).andExpect(status().isOk)

        deleteWithCredentials(email, "WrongPassword1!").andExpectInvalidCredentials()
        deleteWithCredentials(uniqueEmail("nobody"), password).andExpectInvalidCredentials()
        deleteWithCredentials(googleOnly, password).andExpectInvalidCredentials()

        assertTrue(userRepository.existsById(email))
        assertTrue(userRepository.existsById(googleOnly))
    }

    @Test
    fun `nothing is deleted without the confirmation`() {
        val email = uniqueEmail("web-delete-unconfirmed")
        signUp(email)

        deleteWithCredentials(email, password, confirm = false).andExpect(status().isBadRequest)
        assertTrue(userRepository.existsById(email))
    }

    @Test
    fun `guessing one account's password is stopped whatever the IP`() {
        val email = uniqueEmail("web-delete-brute")
        signUp(email)

        repeat(5) { attempt ->
            deleteWithCredentials(email, "Guess$attempt!", clientIp = "198.51.100.$attempt")
                .andExpectInvalidCredentials()
        }
        // Even the right password from a new IP: the email's attempts are spent.
        deleteWithCredentials(email, password, clientIp = "198.51.100.99")
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
        assertTrue(userRepository.existsById(email))
    }

    @Test
    fun `one IP trying many accounts is stopped`() {
        repeat(20) { deleteWithCredentials(uniqueEmail("spray"), password).andExpectInvalidCredentials() }
        deleteWithCredentials(uniqueEmail("spray"), password)
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
    }

    @Test
    fun `a valid Google token deletes the account it opens`() {
        val email = uniqueEmail("web-delete-google")
        signUp(email)
        makePremium(email)
        googleTokenVerifier.next = GoogleUserInfo("subject-${UUID.randomUUID()}", email.uppercase(), true, "G")

        deleteWithGoogle()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deleted").value(true))
            .andExpect(jsonPath("$.cancelSubscriptionInStore").value(true))
        assertFalse(userRepository.existsById(email))
    }

    @Test
    fun `an invalid or unverified Google token deletes nothing`() {
        val email = uniqueEmail("web-delete-google-bad")
        signUp(email)

        googleTokenVerifier.next = null
        deleteWithGoogle().andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_GOOGLE_TOKEN"))

        googleTokenVerifier.next = GoogleUserInfo("subject-${UUID.randomUUID()}", email, false, "G")
        deleteWithGoogle().andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_GOOGLE_TOKEN"))

        googleTokenVerifier.next = GoogleUserInfo("subject-${UUID.randomUUID()}", email, true, "G")
        deleteWithGoogle(confirm = false).andExpect(status().isBadRequest)

        assertTrue(userRepository.existsById(email))
    }

    @Test
    fun `a Google account with no BudgetHunter account answers 404`() {
        googleTokenVerifier.next = GoogleUserInfo("subject-${UUID.randomUUID()}", uniqueEmail("stranger"), true, "G")
        deleteWithGoogle().andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NO_ACCOUNT"))
    }

    @Test
    fun `neither the password nor the token reach the logs`(output: CapturedOutput) {
        val email = uniqueEmail("web-delete-logs")
        signUp(email)
        val secret = "Never-Logged-${UUID.randomUUID()}"

        deleteWithCredentials(email, secret).andExpectInvalidCredentials()
        googleTokenVerifier.next = null
        mockMvc.perform(
            post("/api/users/delete_with_google").contentType(MediaType.APPLICATION_JSON).header("X-Real-IP", ip)
                .content(objectMapper.writeValueAsString(mapOf("idToken" to "token-$secret", "confirm" to true)))
        ).andExpect(status().isUnauthorized)

        assertThat(output.all, not(containsString(secret)))
    }
}
