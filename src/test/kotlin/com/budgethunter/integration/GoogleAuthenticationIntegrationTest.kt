package com.budgethunter.integration

import com.budgethunter.dto.AddCollaboratorRequest
import com.budgethunter.dto.BudgetResponse
import com.budgethunter.dto.CreateBudgetRequest
import com.budgethunter.dto.GoogleSignInRequest
import com.budgethunter.dto.RefreshTokenRequest
import com.budgethunter.dto.SetPasswordRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.model.AuthProvider
import com.budgethunter.repository.UserRepository
import com.budgethunter.util.GoogleTokenVerifier
import com.budgethunter.util.GoogleUserInfo
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * Stands in for Google so the tests never leave the machine.
 *
 * Registered as [Primary] so it wins over the real `GoogleIdTokenVerifierAdapter` component;
 * each test sets [next] to the identity it wants the token to resolve to, or null to have the
 * verifier reject it the way an expired or foreign-audience token would.
 */
class StubGoogleTokenVerifier : GoogleTokenVerifier {
    var next: GoogleUserInfo? = null

    override fun verify(idToken: String): GoogleUserInfo =
        next ?: throw BadCredentialsException("Invalid Google ID token")
}

@TestConfiguration
class StubGoogleTokenVerifierConfig {
    @Bean
    @Primary
    fun stubGoogleTokenVerifier(): StubGoogleTokenVerifier = StubGoogleTokenVerifier()
}

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubGoogleTokenVerifierConfig::class)
@Transactional
class GoogleAuthenticationIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var googleTokenVerifier: StubGoogleTokenVerifier

    private val testEmail = "google-integration@example.com"
    private val testName = "Google Integration User"
    private val testPassword = "TestPassword123!"

    @BeforeEach
    fun setup() {
        googleTokenVerifier.next = GoogleUserInfo(
            subject = "google-subject-integration",
            email = testEmail,
            emailVerified = true,
            name = testName
        )
    }

    private fun signInWithGoogle(idToken: String = "stub-id-token") =
        mockMvc.perform(
            post("/api/users/sign_in_with_google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(GoogleSignInRequest(idToken)))
        )

    @Test
    fun `should create an account and return a usable session on first Google sign in`() {
        val result = signInWithGoogle()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(testEmail))
            .andExpect(jsonPath("$.name").value(testName))
            .andReturn()

        val response = objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java)
        assertTrue(response.authToken.isNotBlank())
        assertTrue(response.refreshToken.isNotBlank())

        val user = userRepository.findById(testEmail).orElseThrow()
        assertNull(user.password)
        assertEquals(AuthProvider.GOOGLE, user.authProvider)
        assertEquals("google-subject-integration", user.googleSubject)
    }

    @Test
    fun `should issue a token that authenticates against a protected endpoint`() {
        val result = signInWithGoogle().andExpect(status().isOk).andReturn()
        val response = objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java)

        mockMvc.perform(
            get("/api/budgets").header("Authorization", "Bearer ${response.authToken}")
        ).andExpect(status().isOk)
    }

    private fun signUp() = mockMvc.perform(
        post("/api/users/sign_up")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(SignUpRequest(testEmail, testName, testPassword)))
    ).andExpect(status().isCreated)

    private fun signIn(password: String = testPassword) = mockMvc.perform(
        post("/api/users/sign_in")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(SignInRequest(testEmail, password)))
    )

    private fun session(result: MvcResult): SignInResponse =
        objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java)

    private fun budgets(authToken: String) =
        mockMvc.perform(get("/api/budgets").header("Authorization", "Bearer $authToken"))

    private fun setPassword(authToken: String, newPassword: String) = mockMvc.perform(
        post("/api/users/password")
            .header("Authorization", "Bearer $authToken")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(SetPasswordRequest(newPassword = newPassword)))
    )

    @Test
    fun `should link a password account by email, removing its password and ending its sessions`() {
        // Someone registered the address with a password and is signed in with it ("pre-account hijacking")
        signUp()
        val before = session(signIn().andExpect(status().isOk).andReturn())
        budgets(before.authToken).andExpect(status().isOk)

        // The owner of the address signs in with Google
        val linked = session(
            signInWithGoogle()
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.email").value(testEmail))
                .andExpect(jsonPath("$.passwordRemoved").value(true))
                .andReturn()
        )

        val user = userRepository.findById(testEmail).orElseThrow()
        assertNull(user.password)
        assertEquals(AuthProvider.GOOGLE, user.authProvider)
        assertEquals("google-subject-integration", user.googleSubject)

        // Everything from before the link is dead: the password, the refresh token and the access token
        signIn().andExpect(status().isUnauthorized)
        mockMvc.perform(
            post("/api/users/refresh_token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RefreshTokenRequest(before.refreshToken)))
        ).andExpect(status().isUnauthorized)
        budgets(before.authToken).andExpect(status().isUnauthorized)
        setPassword(before.authToken, "AttackerPassword1!").andExpect(status().isUnauthorized)

        // The new session works, including its refresh token
        budgets(linked.authToken).andExpect(status().isOk)
        mockMvc.perform(
            post("/api/users/refresh_token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RefreshTokenRequest(linked.refreshToken)))
        ).andExpect(status().isOk)
    }

    private fun createBudget(authToken: String, name: String): Long {
        val result = mockMvc.perform(
            post("/api/budgets")
                .header("Authorization", "Bearer $authToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CreateBudgetRequest(name = name, amount = BigDecimal("100"))))
        ).andExpect(status().isCreated).andReturn()
        return objectMapper.readValue(result.response.contentAsString, BudgetResponse::class.java).id
    }

    private fun addCollaborator(authToken: String, budgetId: Long, email: String) = mockMvc.perform(
        post("/api/budgets/$budgetId/collaborators")
            .header("Authorization", "Bearer $authToken")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(AddCollaboratorRequest(budgetId = budgetId, email = email)))
    ).andExpect(status().isCreated)

    private fun budgetIds(authToken: String): List<Long> =
        objectMapper.readValue(
            budgets(authToken).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<BudgetResponse>::class.java
        ).map { it.id }

    @Test
    fun `should unshare the budgets of a password account linked by email`() {
        // The squatter shares a budget of the account with an accomplice, and joins one of the accomplice's
        signUp()
        val squatter = session(signIn().andExpect(status().isOk).andReturn())
        val accompliceEmail = "accomplice@example.com"
        mockMvc.perform(
            post("/api/users/sign_up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignUpRequest(accompliceEmail, "Accomplice", testPassword)))
        ).andExpect(status().isCreated)
        val accomplice = session(
            mockMvc.perform(
                post("/api/users/sign_in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(SignInRequest(accompliceEmail, testPassword)))
            ).andExpect(status().isOk).andReturn()
        )
        val ownBudget = createBudget(squatter.authToken, "Gastos")
        addCollaborator(squatter.authToken, ownBudget, accompliceEmail)
        val accompliceBudget = createBudget(accomplice.authToken, "Casa")
        addCollaborator(accomplice.authToken, accompliceBudget, testEmail)

        val linked = session(signInWithGoogle().andExpect(status().isOk).andReturn())

        // The owner keeps the account's own budget, alone; the accomplice keeps only theirs, alone
        assertEquals(listOf(ownBudget), budgetIds(linked.authToken))
        assertEquals(listOf(accompliceBudget), budgetIds(accomplice.authToken))
        mockMvc.perform(
            get("/api/budgets/$ownBudget/entries").header("Authorization", "Bearer ${accomplice.authToken}")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `should let the owner set a password without the old one after the link`() {
        signUp()
        val linked = session(signInWithGoogle().andExpect(status().isOk).andReturn())

        setPassword(linked.authToken, "OwnersNewPassword1!").andExpect(status().isNoContent)

        signIn("OwnersNewPassword1!").andExpect(status().isOk)
        signIn().andExpect(status().isUnauthorized)
        assertEquals(AuthProvider.PASSWORD_AND_GOOGLE, userRepository.findById(testEmail).orElseThrow().authProvider)
    }

    @Test
    fun `should keep the password of a Google account that set one, on its next Google sign in`() {
        val first = session(signInWithGoogle().andExpect(status().isOk).andReturn())
        setPassword(first.authToken, testPassword).andExpect(status().isNoContent)

        signInWithGoogle()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.passwordRemoved").value(false))

        signIn().andExpect(status().isOk)
        budgets(first.authToken).andExpect(status().isOk)
        assertEquals(AuthProvider.PASSWORD_AND_GOOGLE, userRepository.findById(testEmail).orElseThrow().authProvider)
    }

    @Test
    fun `should let a Google account add a password and then sign in with it`() {
        val result = signInWithGoogle().andExpect(status().isOk).andReturn()
        val response = objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java)

        // No current password is required: the account never had one.
        mockMvc.perform(
            post("/api/users/password")
                .header("Authorization", "Bearer ${response.authToken}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SetPasswordRequest(newPassword = testPassword)))
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            post("/api/users/sign_in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SignInRequest(testEmail, testPassword)))
        ).andExpect(status().isOk)

        val user = userRepository.findById(testEmail).orElseThrow()
        assertEquals(AuthProvider.PASSWORD_AND_GOOGLE, user.authProvider)
    }

    @Test
    fun `should report whether the account has a password`() {
        val result = signInWithGoogle().andExpect(status().isOk).andReturn()
        val response = objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java)

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer ${response.authToken}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(testEmail))
            .andExpect(jsonPath("$.hasPassword").value(false))
            .andExpect(jsonPath("$.authProvider").value("GOOGLE"))
    }

    @Test
    fun `should reject a Google sign in when the email is not verified`() {
        googleTokenVerifier.next = GoogleUserInfo(
            subject = "google-subject-integration",
            email = testEmail,
            emailVerified = false,
            name = testName
        )

        signInWithGoogle().andExpect(status().isUnauthorized)
        assertFalse(userRepository.existsByEmail(testEmail))
    }

    @Test
    fun `should reject a token the verifier does not accept`() {
        googleTokenVerifier.next = null

        signInWithGoogle("expired-token").andExpect(status().isUnauthorized)
    }

    @Test
    fun `should reject a blank ID token before reaching the verifier`() {
        signInWithGoogle("").andExpect(status().isBadRequest)
    }

    @Test
    fun `should require authentication to read the current user or set a password`() {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized)

        mockMvc.perform(
            post("/api/users/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SetPasswordRequest(newPassword = testPassword)))
        ).andExpect(status().isUnauthorized)
    }
}
