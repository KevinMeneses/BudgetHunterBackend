package com.budgethunter.service

import com.budgethunter.dto.GoogleSignInRequest
import com.budgethunter.dto.RefreshTokenRequest
import com.budgethunter.dto.SetPasswordRequest
import com.budgethunter.dto.SharedBudgetNotice
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.exception.DuplicateEmailException
import com.budgethunter.model.AuthProvider
import com.budgethunter.model.User
import com.budgethunter.privacy.PRIVACY_POLICY_VERSION
import com.budgethunter.repository.BudgetMemberRow
import com.budgethunter.repository.PrivacyPolicyAcceptanceRepository
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import com.budgethunter.util.GoogleTokenVerifier
import com.budgethunter.util.GoogleUserInfo
import com.budgethunter.util.JwtUtil
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.util.Optional

class UserServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var jwtUtil: JwtUtil
    private lateinit var googleTokenVerifier: GoogleTokenVerifier
    private lateinit var privacyPolicyAcceptanceRepository: PrivacyPolicyAcceptanceRepository
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var userBudgetRepository: UserBudgetRepository
    private lateinit var userService: UserService

    @BeforeEach
    fun setup() {
        userRepository = mockk()
        passwordEncoder = mockk()
        jwtUtil = mockk()
        googleTokenVerifier = mockk()
        // Strict: any acceptance recorded that a test did not stub fails it.
        privacyPolicyAcceptanceRepository = mockk()
        eventPublisher = mockk(relaxed = true)
        userBudgetRepository = mockk()
        userService = UserService(
            userRepository,
            passwordEncoder,
            jwtUtil,
            googleTokenVerifier,
            privacyPolicyAcceptanceRepository,
            userBudgetRepository,
            eventPublisher
        )
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
        every { userRepository.insert(any()) } returns savedUser

        // When
        val result = userService.signUp(request)

        // Then
        assertEquals(request.email, result.email)
        assertEquals(request.name, result.name)

        verify(exactly = 1) { userRepository.existsByEmail(request.email) }
        verify(exactly = 1) { passwordEncoder.encode(request.password) }
        verify(exactly = 1) { userRepository.insert(any()) }
        // save() would merge over an account created concurrently
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `signUp should answer DuplicateEmailException when a concurrent sign-up wins the insert`() {
        // Given - the existence check passed, but another request created the account first
        val request = SignUpRequest(email = "race@example.com", name = "Test User", password = "password123")
        every { userRepository.existsByEmail(request.email) } returns false
        every { passwordEncoder.encode(request.password) } returns "encodedPassword"
        every { userRepository.insert(any()) } throws DataIntegrityViolationException("duplicate key")

        // When / Then
        val exception = assertThrows<DuplicateEmailException> { userService.signUp(request) }
        assertEquals("Email already exists", exception.message)
        verify(exactly = 0) { userRepository.save(any()) }
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
        val exception = assertThrows<DuplicateEmailException> {
            userService.signUp(request)
        }

        assertEquals("Email already exists", exception.message)
        verify(exactly = 1) { userRepository.existsByEmail(request.email) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.insert(any()) }
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
        every { jwtUtil.generateToken(user.email, 0) } returns authToken
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
        verify(exactly = 1) { jwtUtil.generateToken(user.email, 0) }
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
        verify(exactly = 0) { jwtUtil.generateToken(any(), any()) }
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
        every { jwtUtil.generateToken(user.email, 0) } returns newAuthToken
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
        verify(exactly = 1) { jwtUtil.generateToken(user.email, 0) }
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
        verify(exactly = 0) { jwtUtil.generateToken(any(), any()) }
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
        verify(exactly = 0) { jwtUtil.generateToken(any(), any()) }
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
        verify(exactly = 0) { jwtUtil.generateToken(any(), any()) }
    }

    // Google Sign-In Tests

    private fun googleInfo(
        subject: String = "google-sub-123",
        email: String = "test@example.com",
        emailVerified: Boolean = true,
        name: String? = "Test User"
    ) = GoogleUserInfo(subject, email, emailVerified, name)

    private fun stubSessionIssuing() {
        every { jwtUtil.generateToken(any(), any()) } returns "authToken"
        every { jwtUtil.generateRefreshToken() } returns "refreshToken"
        every { jwtUtil.getRefreshTokenExpiry() } returns Instant.now().plusSeconds(3600)
        every { userRepository.insert(any()) } answers { firstArg() }
        every { userRepository.save(any()) } answers { firstArg() }
    }

    @Test
    fun `signInWithGoogle should create a new user when the Google account is unknown`() {
        // Given
        val request = GoogleSignInRequest("id-token")
        every { googleTokenVerifier.verify(request.idToken) } returns googleInfo()
        every { userRepository.findByGoogleSubject("google-sub-123") } returns Optional.empty()
        every { userRepository.lockForUpdate("test@example.com") } returns null
        stubSessionIssuing()

        // When
        val result = userService.signInWithGoogle(request)

        // Then
        assertEquals("test@example.com", result.email)
        assertEquals("Test User", result.name)
        assertEquals("authToken", result.authToken)

        // Created with an INSERT, so it can never merge over an account made concurrently
        val inserted = slot<User>()
        verify(exactly = 1) { userRepository.insert(capture(inserted)) }
        assertEquals(AuthProvider.GOOGLE, inserted.captured.authProvider)
        assertNull(inserted.captured.password)
        assertEquals("google-sub-123", inserted.captured.googleSubject)
    }

    @Test
    fun `signInWithGoogle records the acceptance on an existing account only with the current version`() {
        val existing = User(email = "test@example.com", name = "Test User", password = null, googleSubject = "google-sub-123")
        every { userRepository.findByGoogleSubject("google-sub-123") } returns Optional.of(existing)
        every { privacyPolicyAcceptanceRepository.recordAcceptance(any(), any(), any()) } returns 1
        stubSessionIssuing()

        listOf(null, "2025-01-01").forEach { version ->
            every { googleTokenVerifier.verify("id-token") } returns googleInfo()
            userService.signInWithGoogle(GoogleSignInRequest("id-token", version))
        }
        verify(exactly = 0) { privacyPolicyAcceptanceRepository.recordAcceptance(any(), any(), any()) }

        userService.signInWithGoogle(GoogleSignInRequest("id-token", PRIVACY_POLICY_VERSION))
        verify(exactly = 1) {
            privacyPolicyAcceptanceRepository.recordAcceptance("test@example.com", PRIVACY_POLICY_VERSION, any())
        }
    }

    @Test
    fun `signUp stores the current version on the new account and ignores any other`() {
        every { userRepository.existsByEmail(any()) } returns false
        every { passwordEncoder.encode(any()) } returns "encoded"
        val inserted = mutableListOf<User>()
        every { userRepository.insert(capture(inserted)) } answers { firstArg() }

        userService.signUp(SignUpRequest("a@example.com", "A", "password1", PRIVACY_POLICY_VERSION))
        userService.signUp(SignUpRequest("b@example.com", "B", "password1", "2025-01-01"))

        assertEquals(PRIVACY_POLICY_VERSION, inserted[0].privacyPolicyVersion)
        assertNotNull(inserted[0].privacyPolicyAcceptedAt)
        assertNull(inserted[1].privacyPolicyVersion)
        assertNull(inserted[1].privacyPolicyAcceptedAt)
    }

    @Test
    fun `signInWithGoogle should not overwrite an account created concurrently`() {
        // Given - a password sign-up for the same email committed between our lookup and insert
        val request = GoogleSignInRequest("id-token")
        every { googleTokenVerifier.verify(request.idToken) } returns googleInfo()
        every { userRepository.findByGoogleSubject("google-sub-123") } returns Optional.empty()
        every { userRepository.lockForUpdate("test@example.com") } returns null
        every { userRepository.insert(any()) } throws DataIntegrityViolationException("duplicate key")

        // When / Then - a retry will find the account and link it
        assertThrows<DuplicateEmailException> { userService.signInWithGoogle(request) }
        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { jwtUtil.generateToken(any(), any()) }
    }

    @Test
    fun `signInWithGoogle should link an existing password account and remove its password`() {
        // Given
        val request = GoogleSignInRequest("id-token")
        val existing = User(
            email = "test@example.com",
            name = "Existing User",
            password = "encodedPassword",
            authProvider = AuthProvider.PASSWORD
        )
        every { googleTokenVerifier.verify(request.idToken) } returns googleInfo()
        every { userRepository.findByGoogleSubject("google-sub-123") } returns Optional.empty()
        every { userRepository.lockForUpdate("test@example.com") } returns existing
        every { userBudgetRepository.findOtherMembersOfBudgetsOf("test@example.com") } returns listOf(
            memberRow(3L, "Gastos", "test@example.com", "accomplice@example.com"),
            memberRow(5L, "Casa", "owner@example.com", "owner@example.com"),
            memberRow(5L, "Casa", "owner@example.com", "third@example.com")
        )
        stubSessionIssuing()

        // When
        val result = userService.signInWithGoogle(request)

        // Then - the budgets it shares are listed for the user to review, and none is removed
        assertEquals(
            listOf(
                SharedBudgetNotice(3L, "Gastos", "test@example.com", listOf("accomplice@example.com")),
                SharedBudgetNotice(5L, "Casa", "owner@example.com", listOf("owner@example.com", "third@example.com"))
            ),
            result.sharedBudgets
        )

        // and the account is reused, so the user keeps their budgets
        assertEquals("test@example.com", result.email)
        assertEquals("Existing User", result.name)
        // and the password nobody can vouch for is gone, with every session it had
        assertEquals(AuthProvider.GOOGLE, existing.authProvider)
        assertEquals("google-sub-123", existing.googleSubject)
        assertNull(existing.password)
        assertEquals(1, existing.tokenVersion)
        assertTrue(result.passwordRemoved)
        verify(exactly = 1) { jwtUtil.generateToken("test@example.com", 1) }
        verify(exactly = 1) { eventPublisher.publishEvent(SessionsEndedEvent("test@example.com")) }
    }

    @Test
    fun `signInWithGoogle should clear a shared default budget of the linked account`() {
        // Given - the default budget, where the app records SMS entries on its own, is shared with someone
        val request = GoogleSignInRequest("id-token")
        val existing = User(
            email = "test@example.com",
            name = "Test User",
            password = "encodedPassword",
            defaultBudgetId = 7L
        )
        every { googleTokenVerifier.verify(request.idToken) } returns googleInfo()
        every { userRepository.findByGoogleSubject("google-sub-123") } returns Optional.empty()
        every { userRepository.lockForUpdate("test@example.com") } returns existing
        every { userBudgetRepository.findOtherMembersOfBudgetsOf("test@example.com") } returns
            listOf(memberRow(7L, "Gastos", "test@example.com", "accomplice@example.com"))
        stubSessionIssuing()

        // When
        userService.signInWithGoogle(request)

        // Then
        assertNull(existing.defaultBudgetId)
    }

    @Test
    fun `signInWithGoogle should keep a default budget the linked account has to itself`() {
        val request = GoogleSignInRequest("id-token")
        val existing = User(email = "test@example.com", name = "Test User", password = "encodedPassword", defaultBudgetId = 7L)
        every { googleTokenVerifier.verify(request.idToken) } returns googleInfo()
        every { userRepository.findByGoogleSubject("google-sub-123") } returns Optional.empty()
        every { userRepository.lockForUpdate("test@example.com") } returns existing
        every { userBudgetRepository.findOtherMembersOfBudgetsOf("test@example.com") } returns emptyList()
        stubSessionIssuing()

        val result = userService.signInWithGoogle(request)

        assertEquals(7L, existing.defaultBudgetId)
        assertTrue(result.passwordRemoved)
        assertTrue(result.sharedBudgets.isEmpty())
    }

    private fun memberRow(id: Long, name: String, owner: String, member: String) = object : BudgetMemberRow {
        override val budgetId = id
        override val budgetName = name
        override val ownerEmail = owner
        override val memberEmail = member
    }

    @Test
    fun `signInWithGoogle should keep the password of an account that already had the Google subject`() {
        // Given - signed up with Google, then set a password while signed in (POST /api/users/password)
        val request = GoogleSignInRequest("id-token")
        val existing = User(
            email = "test@example.com",
            name = "Test User",
            password = "encodedPassword",
            googleSubject = "google-sub-123",
            authProvider = AuthProvider.PASSWORD_AND_GOOGLE
        )
        every { googleTokenVerifier.verify(request.idToken) } returns googleInfo()
        every { userRepository.findByGoogleSubject("google-sub-123") } returns Optional.of(existing)
        stubSessionIssuing()

        // When
        val result = userService.signInWithGoogle(request)

        // Then
        assertEquals("encodedPassword", existing.password)
        assertEquals(AuthProvider.PASSWORD_AND_GOOGLE, existing.authProvider)
        assertEquals(0, existing.tokenVersion)
        assertFalse(result.passwordRemoved)
        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
        verify(exactly = 0) { userBudgetRepository.findOtherMembersOfBudgetsOf(any()) }
    }

    @Test
    fun `signInWithGoogle should link an account without a password as before`() {
        // Given
        val request = GoogleSignInRequest("id-token")
        val existing = User(email = "test@example.com", name = "Test User", password = null)
        every { googleTokenVerifier.verify(request.idToken) } returns googleInfo()
        every { userRepository.findByGoogleSubject("google-sub-123") } returns Optional.empty()
        every { userRepository.lockForUpdate("test@example.com") } returns existing
        stubSessionIssuing()

        // When
        val result = userService.signInWithGoogle(request)

        // Then
        assertEquals(AuthProvider.GOOGLE, existing.authProvider)
        assertEquals("google-sub-123", existing.googleSubject)
        assertEquals(0, existing.tokenVersion)
        assertFalse(result.passwordRemoved)
        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
        verify(exactly = 0) { userBudgetRepository.findOtherMembersOfBudgetsOf(any()) }
    }

    @Test
    fun `signInWithGoogle should match on the Google subject without rewriting a changed email`() {
        // Given - the account was created under an address the user has since changed
        val request = GoogleSignInRequest("id-token")
        val existing = User(
            email = "old@example.com",
            name = "Test User",
            password = null,
            googleSubject = "google-sub-123",
            authProvider = AuthProvider.GOOGLE
        )
        every { googleTokenVerifier.verify(request.idToken) } returns googleInfo(email = "new@example.com")
        every { userRepository.findByGoogleSubject("google-sub-123") } returns Optional.of(existing)
        stubSessionIssuing()

        // When
        val result = userService.signInWithGoogle(request)

        // Then - the email is the primary key and foreign keys point at it, so it stays put
        assertEquals("old@example.com", result.email)
        assertEquals("old@example.com", existing.email)
        verify(exactly = 0) { userRepository.lockForUpdate(any()) }
    }

    @Test
    fun `signInWithGoogle should lowercase the email before looking the account up`() {
        // Given
        val request = GoogleSignInRequest("id-token")
        every { googleTokenVerifier.verify(request.idToken) } returns googleInfo(email = "Test@Example.COM")
        every { userRepository.findByGoogleSubject("google-sub-123") } returns Optional.empty()
        every { userRepository.lockForUpdate("test@example.com") } returns null
        stubSessionIssuing()

        // When
        val result = userService.signInWithGoogle(request)

        // Then
        assertEquals("test@example.com", result.email)
        verify(exactly = 1) { userRepository.lockForUpdate("test@example.com") }
    }

    @Test
    fun `signInWithGoogle should fall back to the email local part when the name claim is absent`() {
        // Given - name is optional on an ID token but NOT NULL in the database
        val request = GoogleSignInRequest("id-token")
        every { googleTokenVerifier.verify(request.idToken) } returns googleInfo(name = null)
        every { userRepository.findByGoogleSubject("google-sub-123") } returns Optional.empty()
        every { userRepository.lockForUpdate("test@example.com") } returns null
        stubSessionIssuing()

        // When
        val result = userService.signInWithGoogle(request)

        // Then
        assertEquals("test", result.name)
    }

    @Test
    fun `signInWithGoogle should reject an unverified email`() {
        // Given - linking by email is only safe while Google vouches for it
        val request = GoogleSignInRequest("id-token")
        every { googleTokenVerifier.verify(request.idToken) } returns googleInfo(emailVerified = false)

        // When / Then
        assertThrows<BadCredentialsException> { userService.signInWithGoogle(request) }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `signInWithGoogle should propagate a rejection from the verifier`() {
        // Given
        val request = GoogleSignInRequest("bad-token")
        every { googleTokenVerifier.verify(request.idToken) } throws BadCredentialsException("Invalid Google ID token")

        // When / Then
        assertThrows<BadCredentialsException> { userService.signInWithGoogle(request) }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `signIn should reject a Google-only account instead of failing on a null password`() {
        // Given
        val request = SignInRequest(email = "test@example.com", password = "password123")
        val googleOnlyUser = User(
            email = request.email,
            name = "Test User",
            password = null,
            googleSubject = "google-sub-123",
            authProvider = AuthProvider.GOOGLE
        )
        every { userRepository.findById(request.email) } returns Optional.of(googleOnlyUser)

        // When / Then
        assertThrows<BadCredentialsException> { userService.signIn(request) }
        verify(exactly = 0) { passwordEncoder.matches(any(), any()) }
    }

    // Set Password Tests

    @Test
    fun `setPassword should not require a current password when the account has none`() {
        // Given - this is how an account created through Google gains a password
        val user = User(
            email = "test@example.com",
            name = "Test User",
            password = null,
            googleSubject = "google-sub-123",
            authProvider = AuthProvider.GOOGLE
        )
        every { userRepository.lockForUpdate("test@example.com") } returns user
        every { passwordEncoder.encode("newPassword") } returns "encodedNewPassword"
        every { userRepository.save(any()) } answers { firstArg() }

        // When
        userService.setPassword("test@example.com", SetPasswordRequest(newPassword = "newPassword"), 0)

        // Then
        assertEquals("encodedNewPassword", user.password)
        assertEquals(AuthProvider.PASSWORD_AND_GOOGLE, user.authProvider)
        verify(exactly = 0) { passwordEncoder.matches(any(), any()) }
    }

    @Test
    fun `setPassword should require the current password when the account already has one`() {
        // Given
        val user = User(email = "test@example.com", name = "Test User", password = "encodedPassword")
        every { userRepository.lockForUpdate("test@example.com") } returns user

        // When / Then
        assertThrows<BadCredentialsException> {
            userService.setPassword("test@example.com", SetPasswordRequest(newPassword = "newPassword"), 0)
        }
        assertEquals("encodedPassword", user.password)
    }

    @Test
    fun `setPassword should reject an incorrect current password`() {
        // Given
        val user = User(email = "test@example.com", name = "Test User", password = "encodedPassword")
        every { userRepository.lockForUpdate("test@example.com") } returns user
        every { passwordEncoder.matches("wrongPassword", "encodedPassword") } returns false

        // When / Then
        assertThrows<BadCredentialsException> {
            userService.setPassword(
                "test@example.com",
                SetPasswordRequest(currentPassword = "wrongPassword", newPassword = "newPassword"),
                0
            )
        }
        assertEquals("encodedPassword", user.password)
    }

    @Test
    fun `setPassword should replace the password when the current one matches`() {
        // Given
        val user = User(email = "test@example.com", name = "Test User", password = "encodedPassword")
        every { userRepository.lockForUpdate("test@example.com") } returns user
        every { passwordEncoder.matches("oldPassword", "encodedPassword") } returns true
        every { passwordEncoder.encode("newPassword") } returns "encodedNewPassword"
        every { userRepository.save(any()) } answers { firstArg() }

        // When
        userService.setPassword(
            "test@example.com",
            SetPasswordRequest(currentPassword = "oldPassword", newPassword = "newPassword"),
            0
        )

        // Then
        assertEquals("encodedNewPassword", user.password)
        assertEquals(AuthProvider.PASSWORD, user.authProvider)
    }

    @Test
    fun `setPassword should reject a session that ended while the request was on its way`() {
        // Given - a Google link removed the password and raised the version after the request passed the JWT filter
        val user = User(
            email = "test@example.com",
            name = "Test User",
            password = null,
            googleSubject = "google-sub-123",
            authProvider = AuthProvider.GOOGLE,
            tokenVersion = 1
        )
        every { userRepository.lockForUpdate("test@example.com") } returns user

        // When / Then
        assertThrows<BadCredentialsException> {
            userService.setPassword("test@example.com", SetPasswordRequest(newPassword = "attackerPassword"), 0)
        }
        assertNull(user.password)
        verify(exactly = 0) { userRepository.save(any()) }
    }

    // Current User Tests

    @Test
    fun `getCurrentUser should report whether the account has a password`() {
        // Given
        val user = User(
            email = "test@example.com",
            name = "Test User",
            password = null,
            googleSubject = "google-sub-123",
            authProvider = AuthProvider.GOOGLE
        )
        every { userRepository.findById("test@example.com") } returns Optional.of(user)

        // When
        val result = userService.getCurrentUser("test@example.com")

        // Then
        assertFalse(result.hasPassword)
        assertEquals(AuthProvider.GOOGLE, result.authProvider)
    }
}
