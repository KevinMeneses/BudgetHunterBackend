package com.budgethunter.integration

import com.budgethunter.dto.RefreshTokenRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.dto.UserResponse
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthenticationIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val testEmail = "integration-test@example.com"
    private val testPassword = "TestPassword123!"
    private val testName = "Integration Test User"

    @BeforeEach
    fun setup() {
        // Each test runs in a transaction that rolls back
    }

    // Sign Up Tests

    @Test
    fun `should successfully sign up a new user`() {
        // Given
        val request = SignUpRequest(
            email = testEmail,
            name = testName,
            password = testPassword
        )

        // When & Then
        val result = mockMvc.perform(
            post("/api/users/sign_up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.email").value(testEmail))
            .andExpect(jsonPath("$.name").value(testName))
            .andReturn()

        val response = objectMapper.readValue(result.response.contentAsString, UserResponse::class.java)
        assertEquals(testEmail, response.email)
        assertEquals(testName, response.name)
    }

    @Test
    fun `should return 400 when signing up with invalid email`() {
        // Given
        val request = SignUpRequest(
            email = "invalid-email",
            name = testName,
            password = testPassword
        )

        // When & Then
        mockMvc.perform(
            post("/api/users/sign_up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `should return 400 when signing up with duplicate email`() {
        // Given - First sign up
        val request = SignUpRequest(
            email = testEmail,
            name = testName,
            password = testPassword
        )

        mockMvc.perform(
            post("/api/users/sign_up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)

        // When & Then - Try to sign up again with same email
        mockMvc.perform(
            post("/api/users/sign_up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
    }

    // Sign In Tests

    @Test
    fun `should successfully sign in with valid credentials`() {
        // Given - Sign up first
        val signUpRequest = SignUpRequest(
            email = testEmail,
            name = testName,
            password = testPassword
        )

        mockMvc.perform(
            post("/api/users/sign_up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signUpRequest))
        )
            .andExpect(status().isCreated)

        // When - Sign in
        val signInRequest = SignInRequest(
            email = testEmail,
            password = testPassword
        )

        val result = mockMvc.perform(
            post("/api/users/sign_in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signInRequest))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authToken").exists())
            .andExpect(jsonPath("$.refreshToken").exists())
            .andExpect(jsonPath("$.email").value(testEmail))
            .andExpect(jsonPath("$.name").value(testName))
            .andReturn()

        val response = objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java)
        assertNotNull(response.authToken)
        assertNotNull(response.refreshToken)
        assertTrue(response.authToken.isNotEmpty())
        assertTrue(response.refreshToken.isNotEmpty())
    }

    @Test
    fun `should return 401 when signing in with non-existent email`() {
        // Given
        val signInRequest = SignInRequest(
            email = "nonexistent@example.com",
            password = testPassword
        )

        // When & Then
        mockMvc.perform(
            post("/api/users/sign_in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signInRequest))
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `should return 401 when signing in with wrong password`() {
        // Given - Sign up first
        val signUpRequest = SignUpRequest(
            email = testEmail,
            name = testName,
            password = testPassword
        )

        mockMvc.perform(
            post("/api/users/sign_up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signUpRequest))
        )
            .andExpect(status().isCreated)

        // When - Try to sign in with wrong password
        val signInRequest = SignInRequest(
            email = testEmail,
            password = "WrongPassword123!"
        )

        mockMvc.perform(
            post("/api/users/sign_in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signInRequest))
        )
            .andExpect(status().isUnauthorized)
    }

    // Refresh Token Tests

    @Test
    fun `should successfully refresh token with valid refresh token`() {
        // Given - Sign up and sign in
        val signUpRequest = SignUpRequest(
            email = testEmail,
            name = testName,
            password = testPassword
        )

        mockMvc.perform(
            post("/api/users/sign_up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signUpRequest))
        )
            .andExpect(status().isCreated)

        val signInRequest = SignInRequest(
            email = testEmail,
            password = testPassword
        )

        val signInResult = mockMvc.perform(
            post("/api/users/sign_in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signInRequest))
        )
            .andExpect(status().isOk)
            .andReturn()

        val signInResponse = objectMapper.readValue(signInResult.response.contentAsString, SignInResponse::class.java)
        val refreshToken = signInResponse.refreshToken

        // When - Refresh token
        val refreshRequest = RefreshTokenRequest(refreshToken = refreshToken)

        val result = mockMvc.perform(
            post("/api/users/refresh_token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refreshRequest))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authToken").exists())
            .andExpect(jsonPath("$.refreshToken").exists())
            .andExpect(jsonPath("$.email").value(testEmail))
            .andReturn()

        val response = objectMapper.readValue(result.response.contentAsString, SignInResponse::class.java)
        assertNotNull(response.authToken)
        assertNotNull(response.refreshToken)
        assertNotEquals(refreshToken, response.refreshToken) // Should rotate refresh token
    }

    @Test
    fun `should return 401 when refreshing with invalid token`() {
        // Given
        val refreshRequest = RefreshTokenRequest(refreshToken = "invalid-refresh-token")

        // When & Then
        mockMvc.perform(
            post("/api/users/refresh_token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refreshRequest))
        )
            .andExpect(status().isUnauthorized)
    }

    // End-to-End Authentication Flow Test

    @Test
    fun `complete authentication flow should work correctly`() {
        // Step 1: Sign up
        val signUpRequest = SignUpRequest(
            email = testEmail,
            name = testName,
            password = testPassword
        )

        mockMvc.perform(
            post("/api/users/sign_up")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signUpRequest))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.email").value(testEmail))

        // Step 2: Sign in
        val signInRequest = SignInRequest(
            email = testEmail,
            password = testPassword
        )

        val signInResult = mockMvc.perform(
            post("/api/users/sign_in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signInRequest))
        )
            .andExpect(status().isOk)
            .andReturn()

        val signInResponse = objectMapper.readValue(signInResult.response.contentAsString, SignInResponse::class.java)
        val originalRefreshToken = signInResponse.refreshToken

        // Step 3: Refresh token
        val refreshRequest = RefreshTokenRequest(refreshToken = originalRefreshToken)

        val refreshResult = mockMvc.perform(
            post("/api/users/refresh_token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refreshRequest))
        )
            .andExpect(status().isOk)
            .andReturn()

        val refreshResponse = objectMapper.readValue(refreshResult.response.contentAsString, SignInResponse::class.java)

        // Verify refresh token rotation (new refresh token should be different)
        assertNotEquals(originalRefreshToken, refreshResponse.refreshToken)
        // Note: Auth tokens may be the same if generated within the same second (JWT includes seconds-precision timestamp)
        assertNotNull(refreshResponse.authToken)

        // Step 4: Try to use old refresh token (should fail)
        mockMvc.perform(
            post("/api/users/refresh_token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refreshRequest))
        )
            .andExpect(status().isUnauthorized)
    }
}
