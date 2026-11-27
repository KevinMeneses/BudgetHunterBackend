package com.budgethunter.service

import com.budgethunter.dto.GoogleSignInRequest
import com.budgethunter.dto.SetPasswordRequest
import com.budgethunter.model.AuthProvider
import com.budgethunter.model.User
import com.budgethunter.repository.BudgetMemberRow
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import com.budgethunter.util.GoogleTokenVerifier
import com.budgethunter.util.GoogleUserInfo
import com.budgethunter.util.JwtUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.util.Optional

/**
 * The Google link notice told again until acknowledged (GoogleLinkNoticeService). The link itself is covered in
 * UserServiceTest.
 */
class GoogleLinkNoticeTest {

    private val userRepository = mockk<UserRepository>()
    private val userBudgetRepository = mockk<UserBudgetRepository>()
    private val jwtUtil = mockk<JwtUtil>()
    private val googleTokenVerifier = mockk<GoogleTokenVerifier>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val linkNoticeService = GoogleLinkNoticeService(userRepository, userBudgetRepository)
    private val userService = UserService(
        userRepository,
        mockk { every { encode("newPassword") } returns "encodedNewPassword" },
        jwtUtil,
        googleTokenVerifier,
        mockk(),
        linkNoticeService,
        eventPublisher
    )

    private fun linkedUser(pending: Boolean = true) = User(
        email = "test@example.com",
        name = "Test User",
        password = null,
        googleSubject = "google-sub-123",
        authProvider = AuthProvider.GOOGLE,
        tokenVersion = 1,
        linkNoticePending = pending
    )

    private fun memberRow(id: Long, name: String, owner: String, member: String) = object : BudgetMemberRow {
        override val budgetId = id
        override val budgetName = name
        override val ownerEmail = owner
        override val memberEmail = member
    }

    private fun signInWithGoogleAs(user: User) = run {
        every { googleTokenVerifier.verify("id-token") } returns
            GoogleUserInfo("google-sub-123", "test@example.com", true, "Test User")
        every { userRepository.findByGoogleSubject("google-sub-123") } returns Optional.of(user)
        every { jwtUtil.generateToken(any(), any()) } returns "authToken"
        every { jwtUtil.generateRefreshToken() } returns "refreshToken"
        every { jwtUtil.getRefreshTokenExpiry() } returns Instant.now().plusSeconds(3600)
        every { userRepository.save(any()) } answers { firstArg() }
        userService.signInWithGoogle(GoogleSignInRequest("id-token"))
    }

    @Test
    fun `a Google sign in tells a removed password again until the app acknowledges it`() {
        // Linked earlier, but that answer never reached the app
        val user = linkedUser()
        every { userBudgetRepository.findOtherMembersOfBudgetsOf("test@example.com") } returns
            listOf(memberRow(3L, "Gastos", "test@example.com", "accomplice@example.com"))

        val result = signInWithGoogleAs(user)

        // Told again, without ending the sessions a second time
        assertTrue(result.passwordRemoved)
        assertEquals(listOf("accomplice@example.com"), result.sharedBudgets.single().otherMembers)
        assertEquals(1, user.tokenVersion)
        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
    }

    @Test
    fun `a Google sign in after the acknowledgement tells nothing`() {
        val result = signInWithGoogleAs(linkedUser(pending = false))

        assertFalse(result.passwordRemoved)
        assertTrue(result.sharedBudgets.isEmpty())
        verify(exactly = 0) { userBudgetRepository.findOtherMembersOfBudgetsOf(any()) }
    }

    @Test
    fun `acknowledging clears the notice`() {
        val user = linkedUser()
        every { userRepository.findById("test@example.com") } returns Optional.of(user)

        linkNoticeService.acknowledge("test@example.com")

        assertFalse(user.linkNoticePending)
    }

    @Test
    fun `acknowledging for an unknown account does nothing`() {
        every { userRepository.findById("gone@example.com") } returns Optional.empty()

        linkNoticeService.acknowledge("gone@example.com")
    }

    @Test
    fun `setting a password ends a pending notice`() {
        val user = linkedUser()
        every { userRepository.lockForUpdate("test@example.com") } returns user
        every { userRepository.save(any()) } answers { firstArg() }

        userService.setPassword("test@example.com", SetPasswordRequest(newPassword = "newPassword"), 1)

        assertFalse(user.linkNoticePending)
        assertEquals("encodedNewPassword", user.password)
    }
}
