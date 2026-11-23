package com.budgethunter.integration

import com.budgethunter.dto.AcceptPrivacyPolicyRequest
import com.budgethunter.dto.GoogleSignInRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.privacy.PRIVACY_POLICY_VERSION
import com.budgethunter.repository.UserRepository
import com.budgethunter.util.GoogleUserInfo
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Recording that each account accepted the privacy policy (Ley 1581 de 2012): at sign-up, at a Google sign-in,
 * and from the app with `POST /api/users/me/privacy-policy`; and what `GET /api/users/me` reports.
 *
 * Not `@Transactional`: every request must run in its own transaction, as in production, or a read after the
 * acceptance would get the entity cached by the test's transaction instead of the row. Accounts are removed after.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubGoogleTokenVerifierConfig::class)
class PrivacyPolicyAcceptanceIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var googleTokenVerifier: StubGoogleTokenVerifier

    private val created = mutableListOf<String>()
    private val password = "TestPassword123!"
    private val oldVersion = "2025-01-01"

    @AfterEach
    fun cleanUp() {
        created.forEach { email -> if (userRepository.existsById(email)) userRepository.deleteById(email) }
    }

    // --- sign_up

    @Test
    fun `sign up with the current version records it with the server's date`() {
        val before = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val email = signUp(PRIVACY_POLICY_VERSION).first

        val user = userRepository.findById(email).orElseThrow()
        assertEquals(PRIVACY_POLICY_VERSION, user.privacyPolicyVersion)
        assertBetween(before, user.privacyPolicyAcceptedAt)
    }

    @Test
    fun `sign up without a version, as builds that predate it do, still works and records nothing`() {
        val (email, response) = signUp(null)
        response.andExpect(status().isCreated)

        val user = userRepository.findById(email).orElseThrow()
        assertNull(user.privacyPolicyVersion)
        assertNull(user.privacyPolicyAcceptedAt)
    }

    @Test
    fun `sign up with an old version still works and the version is ignored`() {
        val (email, response) = signUp(oldVersion)
        response.andExpect(status().isCreated)

        val user = userRepository.findById(email).orElseThrow()
        assertNull(user.privacyPolicyVersion)
        assertNull(user.privacyPolicyAcceptedAt)
    }

    // --- sign_in_with_google

    @Test
    fun `a Google sign in that creates the account records the current version`() {
        val email = newEmail()
        val before = Instant.now().truncatedTo(ChronoUnit.MICROS)

        googleSignIn(email, PRIVACY_POLICY_VERSION).andExpect(status().isOk)

        val user = userRepository.findById(email).orElseThrow()
        assertEquals(PRIVACY_POLICY_VERSION, user.privacyPolicyVersion)
        assertBetween(before, user.privacyPolicyAcceptedAt)
    }

    @Test
    fun `a Google sign in that creates the account without a version or with an old one records nothing`() {
        val withoutVersion = newEmail()
        googleSignIn(withoutVersion, null).andExpect(status().isOk)
        val withOldVersion = newEmail()
        googleSignIn(withOldVersion, oldVersion).andExpect(status().isOk)

        listOf(withoutVersion, withOldVersion).forEach { email ->
            assertNull(userRepository.findById(email).orElseThrow().privacyPolicyVersion, email)
        }
    }

    @Test
    fun `a Google sign in to an existing account that has not accepted records it`() {
        // A password account from before acceptances were recorded, now linked to Google.
        val email = signUp(null).first
        val before = Instant.now().truncatedTo(ChronoUnit.MICROS)

        googleSignIn(email, PRIVACY_POLICY_VERSION).andExpect(status().isOk)

        val user = userRepository.findById(email).orElseThrow()
        assertEquals(PRIVACY_POLICY_VERSION, user.privacyPolicyVersion)
        assertBetween(before, user.privacyPolicyAcceptedAt)
        assertNotNull(user.googleSubject, "the link to Google is saved as well")
    }

    @Test
    fun `a Google sign in to an account that already accepted keeps the first date`() {
        val email = newEmail()
        googleSignIn(email, PRIVACY_POLICY_VERSION).andExpect(status().isOk)
        val first = userRepository.findById(email).orElseThrow().privacyPolicyAcceptedAt
        assertNotNull(first)

        googleSignIn(email, PRIVACY_POLICY_VERSION).andExpect(status().isOk)
        googleSignIn(email, oldVersion).andExpect(status().isOk)
        googleSignIn(email, null).andExpect(status().isOk)

        val user = userRepository.findById(email).orElseThrow()
        assertEquals(PRIVACY_POLICY_VERSION, user.privacyPolicyVersion)
        assertEquals(first, user.privacyPolicyAcceptedAt)
    }

    // --- POST /api/users/me/privacy-policy and GET /api/users/me

    @Test
    fun `an account that never accepted is told so by GET me`() {
        val email = signUp(null).first

        authorized(get("/api/users/me"), signIn(email))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.privacyPolicy.currentVersion").value(PRIVACY_POLICY_VERSION))
            .andExpect(jsonPath("$.privacyPolicy.acceptedVersion").doesNotExist())
            .andExpect(jsonPath("$.privacyPolicy.acceptedAt").doesNotExist())
    }

    @Test
    fun `accepting the current version records it and GET me reports it`() {
        val email = signUp(null).first
        val token = signIn(email)
        val before = Instant.now().truncatedTo(ChronoUnit.MICROS)

        val accepted = acceptPolicy(token, PRIVACY_POLICY_VERSION)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.currentVersion").value(PRIVACY_POLICY_VERSION))
            .andExpect(jsonPath("$.acceptedVersion").value(PRIVACY_POLICY_VERSION))
            .andReturn().response.contentAsString
        val acceptedAt = Instant.parse(objectMapper.readTree(accepted)["acceptedAt"].asText())
        assertBetween(before, acceptedAt)

        val user = userRepository.findById(email).orElseThrow()
        assertEquals(PRIVACY_POLICY_VERSION, user.privacyPolicyVersion)
        assertEquals(acceptedAt, user.privacyPolicyAcceptedAt)

        val me = authorized(get("/api/users/me"), token)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.privacyPolicy.currentVersion").value(PRIVACY_POLICY_VERSION))
            .andExpect(jsonPath("$.privacyPolicy.acceptedVersion").value(PRIVACY_POLICY_VERSION))
            .andReturn().response.contentAsString
        assertEquals(acceptedAt, Instant.parse(objectMapper.readTree(me)["privacyPolicy"]["acceptedAt"].asText()))
    }

    @Test
    fun `accepting again keeps the first date`() {
        val email = signUp(PRIVACY_POLICY_VERSION).first
        val first = userRepository.findById(email).orElseThrow().privacyPolicyAcceptedAt

        val answer = acceptPolicy(signIn(email), PRIVACY_POLICY_VERSION)
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertEquals(first, Instant.parse(objectMapper.readTree(answer)["acceptedAt"].asText()))
        assertEquals(first, userRepository.findById(email).orElseThrow().privacyPolicyAcceptedAt)
    }

    @Test
    fun `accepting another version answers 400 and records nothing`() {
        val email = signUp(null).first
        val token = signIn(email)

        acceptPolicy(token, oldVersion).andExpect(status().isBadRequest)
        acceptPolicy(token, "").andExpect(status().isBadRequest)

        assertNull(userRepository.findById(email).orElseThrow().privacyPolicyVersion)
    }

    @Test
    fun `accepting needs an account`() {
        mockMvc.perform(
            post("/api/users/me/privacy-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AcceptPrivacyPolicyRequest(PRIVACY_POLICY_VERSION)))
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `saving the account later does not undo the acceptance`() {
        // Sign-ins and preference changes save the whole entity, read before the acceptance in a real race; the
        // columns are not updatable through it, so those saves cannot write back the nulls they read.
        val email = signUp(null).first
        val token = signIn(email)
        val stale = userRepository.findById(email).orElseThrow()
        acceptPolicy(token, PRIVACY_POLICY_VERSION).andExpect(status().isOk)

        userRepository.save(stale.apply { refreshToken = "stale-${UUID.randomUUID()}" })
        signIn(email)

        val user = userRepository.findById(email).orElseThrow()
        assertEquals(PRIVACY_POLICY_VERSION, user.privacyPolicyVersion)
        assertNotNull(user.privacyPolicyAcceptedAt)
    }

    // --- helpers

    private fun newEmail() = "privacy-${UUID.randomUUID()}@example.com".also { created += it }

    private fun signUp(version: String?): Pair<String, ResultActions> {
        val email = newEmail()
        val response = mockMvc.perform(
            post("/api/users/sign_up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignUpRequest(email, "Privacy User", password, version)))
        ).andExpect(status().isCreated)
        return email to response
    }

    private fun signIn(email: String): String {
        val body = mockMvc.perform(
            post("/api/users/sign_in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignInRequest(email, password)))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, SignInResponse::class.java).authToken
    }

    private fun googleSignIn(email: String, version: String?): ResultActions {
        googleTokenVerifier.next = GoogleUserInfo(
            subject = "subject-$email",
            email = email,
            emailVerified = true,
            name = "Google User"
        )
        return mockMvc.perform(
            post("/api/users/sign_in_with_google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(GoogleSignInRequest("stub-id-token", version)))
        )
    }

    private fun acceptPolicy(token: String, version: String) =
        authorized(
            post("/api/users/me/privacy-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AcceptPrivacyPolicyRequest(version))),
            token
        )

    private fun authorized(
        request: MockHttpServletRequestBuilder,
        token: String
    ) = mockMvc.perform(request.header("Authorization", "Bearer $token"))

    private fun assertBetween(before: Instant, actual: Instant?) {
        assertNotNull(actual, "no acceptance date recorded")
        assertFalse(actual!!.isBefore(before), "$actual is before the request ($before)")
        assertTrue(!actual.isAfter(Instant.now()), "$actual is in the future")
    }
}
