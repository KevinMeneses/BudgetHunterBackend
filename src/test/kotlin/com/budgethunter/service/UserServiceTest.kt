package com.budgethunter.service

import com.budgethunter.dto.RefreshTokenRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.model.User
import com.budgethunter.repository.UserRepository
import com.budgethunter.util.JwtUtil
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.util.*

class UserServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var jwtUtil: JwtUtil
    private lateinit var userService: UserService

    @BeforeEach
    fun setup() {
        userRepository = mockk()
        passwordEncoder = mockk()
        jwtUtil = mockk()
        userService = UserService(userRepository, passwordEncoder, jwtUtil)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // SignUp Tests

    @Test
    fun `signUp should create new user successfully`() {
        // Given
        val request = SignUpRequest(
            email = "test@example.com",
            name = "Test User",
            password = "password123"
        )
        val encodedPassword = "encodedPassword123"
        val savedUser = User(
            email = request.email,
            name = request.name,
            password = encodedPassword
        )

        every { userRepository.existsByEmail(request.email) } returns false
        every { passwordEncoder.encode(request.password) } returns encodedPassword
        every { userRepository.save(any()) } returns savedUser

        // When
        val result = userService.signUp(request)

        // Then
        assertEquals(request.email, result.email)
        assertEquals(request.name, result.name)

        verify(exactly = 1) { userRepository.existsByEmail(request.email) }
        verify(exactly = 1) { passwordEncoder.encode(request.password) }
        verify(exactly = 1) { userRepository.save(any()) }
    }

    @Test
    fun `signUp should throw exception when email already exists`() {
        // Given
        val request = SignUpRequest(
            email = "existing@example.com",
            name = "Test User",
            password = "password123"
        )

        every { userRepository.existsByEmail(request.email) } returns true

        // When & Then
        val exception = assertThrows<IllegalArgumentException> {
            userService.signUp(request)
        }

        assertEquals("Email already exists", exception.message)
        verify(exactly = 1) { userRepository.existsByEmail(request.email) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    // SignIn Tests

    @Test
    fun `signIn should authenticate user successfully`() {
        // Given
        val request = SignInRequest(
            email = "test@example.com",
            password = "password123"
        )
        val user = User(
            email = request.email,
            name = "Test User",
            password = "encodedPassword"
        )
        val authToken = "jwt-auth-token"
        val refreshToken = "refresh-token-uuid"
        val refreshTokenExpiry = Instant.now().plusSeconds(604800)

        every { userRepository.findById(request.email) } returns Optional.of(user)
        every { passwordEncoder.matches(request.password, user.password) } returns true
        every { jwtUtil.generateToken(user.email) } returns authToken
        every { jwtUtil.generateRefreshToken() } returns refreshToken
        every { jwtUtil.getRefreshTokenExpiry() } returns refreshTokenExpiry
        every { userRepository.save(any()) } returns user

        // When
        val result = userService.signIn(request)

        // Then
        assertEquals(authToken, result.authToken)
        assertEquals(refreshToken, result.refreshToken)
        assertEquals(request.email, result.email)
        assertEquals(user.name, result.name)

        verify(exactly = 1) { userRepository.findById(request.email) }
        verify(exactly = 1) { passwordEncoder.matches(request.password, user.password) }
        verify(exactly = 1) { jwtUtil.generateToken(user.email) }
        verify(exactly = 1) { jwtUtil.generateRefreshToken() }
        verify(exactly = 1) { jwtUtil.getRefreshTokenExpiry() }
        verify(exactly = 1) { userRepository.save(any()) }
    }

    @Test
    fun `signIn should throw exception when user not found`() {
        // Given
        val request = SignInRequest(
            email = "nonexistent@example.com",
            password = "password123"
        )

        every { userRepository.findById(request.email) } returns Optional.empty()

        // When & Then
        val exception = assertThrows<BadCredentialsException> {
            userService.signIn(request)
        }

        assertEquals("Invalid email or password", exception.message)
        verify(exactly = 1) { userRepository.findById(request.email) }
        verify(exactly = 0) { passwordEncoder.matches(any(), any()) }
    }

    @Test
    fun `signIn should throw exception when password is incorrect`() {
        // Given
        val request = SignInRequest(
            email = "test@example.com",
            password = "wrongpassword"
        )
        val user = User(
            email = request.email,
            name = "Test User",
            password = "encodedPassword"
        )

        every { userRepository.findById(request.email) } returns Optional.of(user)
        every { passwordEncoder.matches(request.password, user.password) } returns false

        // When & Then
        val exception = assertThrows<BadCredentialsException> {
            userService.signIn(request)
        }

        assertEquals("Invalid email or password", exception.message)
        verify(exactly = 1) { userRepository.findById(request.email) }
        verify(exactly = 1) { passwordEncoder.matches(request.password, user.password) }
        verify(exactly = 0) { jwtUtil.generateToken(any()) }
    }

    // RefreshToken Tests

    @Test
    fun `refreshToken should generate new tokens successfully`() {
        // Given
        val refreshToken = "valid-refresh-token"
        val request = RefreshTokenRequest(refreshToken = refreshToken)
        val user = User(
            email = "test@example.com",
            name = "Test User",
            password = "encodedPassword",
            refreshToken = refreshToken,
            refreshTokenExpiry = Instant.now().plusSeconds(3600)
        )
        val newAuthToken = "new-jwt-auth-token"
        val newRefreshToken = "new-refresh-token-uuid"
        val newRefreshTokenExpiry = Instant.now().plusSeconds(604800)

        every { userRepository.findByRefreshToken(refreshToken) } returns Optional.of(user)
        every { jwtUtil.generateToken(user.email) } returns newAuthToken
        every { jwtUtil.generateRefreshToken() } returns newRefreshToken
        every { jwtUtil.getRefreshTokenExpiry() } returns newRefreshTokenExpiry
        every { userRepository.save(any()) } returns user

        // When
        val result = userService.refreshToken(request)

        // Then
        assertEquals(newAuthToken, result.authToken)
        assertEquals(newRefreshToken, result.refreshToken)
        assertEquals(user.email, result.email)
        assertEquals(user.name, result.name)

        verify(exactly = 1) { userRepository.findByRefreshToken(refreshToken) }
        verify(exactly = 1) { jwtUtil.generateToken(user.email) }
        verify(exactly = 1) { jwtUtil.generateRefreshToken() }
        verify(exactly = 1) { jwtUtil.getRefreshTokenExpiry() }
        verify(exactly = 1) { userRepository.save(any()) }
    }

    @Test
    fun `refreshToken should throw exception when refresh token not found`() {
        // Given
        val refreshToken = "invalid-refresh-token"
        val request = RefreshTokenRequest(refreshToken = refreshToken)

        every { userRepository.findByRefreshToken(refreshToken) } returns Optional.empty()

        // When & Then
        val exception = assertThrows<BadCredentialsException> {
            userService.refreshToken(request)
        }

        assertEquals("Invalid refresh token", exception.message)
        verify(exactly = 1) { userRepository.findByRefreshToken(refreshToken) }
        verify(exactly = 0) { jwtUtil.generateToken(any()) }
    }

    @Test
    fun `refreshToken should throw exception when refresh token is expired`() {
        // Given
        val refreshToken = "expired-refresh-token"
        val request = RefreshTokenRequest(refreshToken = refreshToken)
        val user = User(
            email = "test@example.com",
            name = "Test User",
            password = "encodedPassword",
            refreshToken = refreshToken,
            refreshTokenExpiry = Instant.now().minusSeconds(3600) // Expired 1 hour ago
        )

        every { userRepository.findByRefreshToken(refreshToken) } returns Optional.of(user)

        // When & Then
        val exception = assertThrows<BadCredentialsException> {
            userService.refreshToken(request)
        }

        assertEquals("Refresh token has expired", exception.message)
        verify(exactly = 1) { userRepository.findByRefreshToken(refreshToken) }
        verify(exactly = 0) { jwtUtil.generateToken(any()) }
    }

    @Test
    fun `refreshToken should throw exception when refresh token expiry is null`() {
        // Given
        val refreshToken = "refresh-token-no-expiry"
        val request = RefreshTokenRequest(refreshToken = refreshToken)
        val user = User(
            email = "test@example.com",
            name = "Test User",
            password = "encodedPassword",
            refreshToken = refreshToken,
            refreshTokenExpiry = null
        )

        every { userRepository.findByRefreshToken(refreshToken) } returns Optional.of(user)

        // When & Then
        val exception = assertThrows<BadCredentialsException> {
            userService.refreshToken(request)
        }

        assertEquals("Refresh token has expired", exception.message)
        verify(exactly = 1) { userRepository.findByRefreshToken(refreshToken) }
        verify(exactly = 0) { jwtUtil.generateToken(any()) }
    }
}
