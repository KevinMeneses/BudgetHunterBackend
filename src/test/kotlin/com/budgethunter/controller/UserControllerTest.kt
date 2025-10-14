package com.budgethunter.controller

import com.budgethunter.dto.RefreshTokenRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.dto.UserResponse
import com.budgethunter.service.UserService
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.BadCredentialsException

class UserControllerTest {

    private lateinit var userService: UserService
    private lateinit var userController: UserController

    @BeforeEach
    fun setup() {
        userService = mockk()
        userController = UserController(userService)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // SignUp Tests

    @Test
    fun `signUp should return created status with user response`() {
        // Given
        val request = SignUpRequest(
            email = "test@example.com",
            name = "Test User",
            password = "password123"
        )
        val expectedResponse = UserResponse(
            email = request.email,
            name = request.name
        )

        every { userService.signUp(request) } returns expectedResponse

        // When
        val response = userController.signUp(request)

        // Then
        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertEquals(expectedResponse, response.body)
        verify(exactly = 1) { userService.signUp(request) }
    }

    @Test
    fun `signUp should propagate exception from service`() {
        // Given
        val request = SignUpRequest(
            email = "existing@example.com",
            name = "Test User",
            password = "password123"
        )

        every { userService.signUp(request) } throws IllegalArgumentException("Email already exists")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            userController.signUp(request)
        }

        assertEquals("Email already exists", exception.message)
        verify(exactly = 1) { userService.signUp(request) }
    }

    // SignIn Tests

    @Test
    fun `signIn should return ok status with sign in response`() {
        // Given
        val request = SignInRequest(
            email = "test@example.com",
            password = "password123"
        )
        val expectedResponse = SignInResponse(
            authToken = "jwt-token",
            refreshToken = "refresh-token",
            email = request.email,
            name = "Test User"
        )

        every { userService.signIn(request) } returns expectedResponse

        // When
        val response = userController.signIn(request)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(expectedResponse, response.body)
        assertEquals("jwt-token", response.body?.authToken)
        assertEquals("refresh-token", response.body?.refreshToken)
        verify(exactly = 1) { userService.signIn(request) }
    }

    @Test
    fun `signIn should propagate BadCredentialsException from service`() {
        // Given
        val request = SignInRequest(
            email = "test@example.com",
            password = "wrongpassword"
        )

        every { userService.signIn(request) } throws BadCredentialsException("Invalid email or password")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<BadCredentialsException> {
            userController.signIn(request)
        }

        assertEquals("Invalid email or password", exception.message)
        verify(exactly = 1) { userService.signIn(request) }
    }

    // RefreshToken Tests

    @Test
    fun `refreshToken should return ok status with new tokens`() {
        // Given
        val request = RefreshTokenRequest(refreshToken = "valid-refresh-token")
        val expectedResponse = SignInResponse(
            authToken = "new-jwt-token",
            refreshToken = "new-refresh-token",
            email = "test@example.com",
            name = "Test User"
        )

        every { userService.refreshToken(request) } returns expectedResponse

        // When
        val response = userController.refreshToken(request)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(expectedResponse, response.body)
        assertEquals("new-jwt-token", response.body?.authToken)
        assertEquals("new-refresh-token", response.body?.refreshToken)
        verify(exactly = 1) { userService.refreshToken(request) }
    }

    @Test
    fun `refreshToken should propagate exception when token is invalid`() {
        // Given
        val request = RefreshTokenRequest(refreshToken = "invalid-token")

        every { userService.refreshToken(request) } throws BadCredentialsException("Invalid refresh token")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<BadCredentialsException> {
            userController.refreshToken(request)
        }

        assertEquals("Invalid refresh token", exception.message)
        verify(exactly = 1) { userService.refreshToken(request) }
    }

    @Test
    fun `refreshToken should propagate exception when token is expired`() {
        // Given
        val request = RefreshTokenRequest(refreshToken = "expired-token")

        every { userService.refreshToken(request) } throws BadCredentialsException("Refresh token has expired")

        // When & Then
        val exception = org.junit.jupiter.api.assertThrows<BadCredentialsException> {
            userController.refreshToken(request)
        }

        assertEquals("Refresh token has expired", exception.message)
        verify(exactly = 1) { userService.refreshToken(request) }
    }

    // Integration-like Tests

    @Test
    fun `signUp and signIn flow should work correctly`() {
        // Given - Sign up a new user
        val signUpRequest = SignUpRequest(
            email = "newuser@example.com",
            name = "New User",
            password = "password123"
        )
        val userResponse = UserResponse(
            email = signUpRequest.email,
            name = signUpRequest.name
        )

        every { userService.signUp(signUpRequest) } returns userResponse

        // When - Sign up
        val signUpResponse = userController.signUp(signUpRequest)

        // Then - Verify sign up
        assertEquals(HttpStatus.CREATED, signUpResponse.statusCode)
        assertEquals(signUpRequest.email, signUpResponse.body?.email)

        // Given - Sign in with the new user
        val signInRequest = SignInRequest(
            email = signUpRequest.email,
            password = signUpRequest.password
        )
        val signInResponse = SignInResponse(
            authToken = "jwt-token",
            refreshToken = "refresh-token",
            email = signUpRequest.email,
            name = signUpRequest.name
        )

        every { userService.signIn(signInRequest) } returns signInResponse

        // When - Sign in
        val signInResult = userController.signIn(signInRequest)

        // Then - Verify sign in
        assertEquals(HttpStatus.OK, signInResult.statusCode)
        assertNotNull(signInResult.body?.authToken)
        assertNotNull(signInResult.body?.refreshToken)

        verify(exactly = 1) { userService.signUp(signUpRequest) }
        verify(exactly = 1) { userService.signIn(signInRequest) }
    }
}
