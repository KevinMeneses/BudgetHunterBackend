package com.budgethunter.service

import com.budgethunter.dto.UpdateUserPreferencesRequest
import com.budgethunter.exception.ForbiddenAccessException
import com.budgethunter.model.User
import com.budgethunter.model.UserBudgetId
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.authentication.BadCredentialsException
import java.util.Optional

class UserPreferencesServiceTest {

    private val email = "test@example.com"
    private lateinit var userRepository: UserRepository
    private lateinit var userBudgetRepository: UserBudgetRepository
    private lateinit var service: UserPreferencesService
    private lateinit var user: User

    @BeforeEach
    fun setup() {
        userRepository = mockk()
        userBudgetRepository = mockk()
        service = UserPreferencesService(userRepository, userBudgetRepository)
        user = User(email = email, name = "Test")
        every { userRepository.findById(email) } returns Optional.of(user)
        every { userRepository.save(any<User>()) } answers { firstArg() }
    }

    @Test
    fun `get returns nulls for an account that never saved preferences`() {
        val result = service.get(email)

        assertNull(result.smsReadingEnabled)
        assertNull(result.aiProcessingEnabled)
        assertNull(result.defaultBudgetId)
        assertNull(result.selectedBankIds)
    }

    @Test
    fun `get fails for an unknown user`() {
        every { userRepository.findById("nobody") } returns Optional.empty()

        assertThrows<BadCredentialsException> { service.get("nobody") }
    }

    @Test
    fun `update stores every preference and get returns them`() {
        every { userBudgetRepository.existsById(UserBudgetId(7L, email)) } returns true

        val result = service.update(
            email,
            UpdateUserPreferencesRequest(
                smsReadingEnabled = true,
                aiProcessingEnabled = false,
                defaultBudgetId = 7L,
                selectedBankIds = listOf("bancolombia", "nequi", "nequi")
            )
        )

        assertEquals(true, result.smsReadingEnabled)
        assertEquals(false, result.aiProcessingEnabled)
        assertEquals(7L, result.defaultBudgetId)
        assertEquals(listOf("bancolombia", "nequi"), result.selectedBankIds)
        verify { userRepository.save(user) }
    }

    @Test
    fun `update with no banks stores an empty list rather than never saved`() {
        val result = service.update(
            email,
            UpdateUserPreferencesRequest(smsReadingEnabled = false, aiProcessingEnabled = true)
        )

        assertEquals(emptyList<String>(), result.selectedBankIds)
        assertNull(result.defaultBudgetId)
    }

    @Test
    fun `update rejects a default budget the user cannot access`() {
        every { userBudgetRepository.existsById(UserBudgetId(9L, email)) } returns false

        assertThrows<ForbiddenAccessException> {
            service.update(
                email,
                UpdateUserPreferencesRequest(
                    smsReadingEnabled = true,
                    aiProcessingEnabled = true,
                    defaultBudgetId = 9L
                )
            )
        }
        verify(exactly = 0) { userRepository.save(any<User>()) }
    }

    @Test
    fun `update rejects bank ids that would corrupt the stored list`() {
        assertThrows<IllegalArgumentException> {
            service.update(
                email,
                UpdateUserPreferencesRequest(
                    smsReadingEnabled = true,
                    aiProcessingEnabled = true,
                    selectedBankIds = listOf("a,b")
                )
            )
        }
    }

    @Test
    fun `get drops a default budget the user can no longer reach`() {
        user.defaultBudgetId = 3L
        every { userBudgetRepository.existsById(UserBudgetId(3L, email)) } returns false

        assertNull(service.get(email).defaultBudgetId)
    }
}
