package com.budgethunter.service

import com.budgethunter.model.UserBudgetId
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

class StreamAccessGuardTest {
    private val userRepository = mockk<UserRepository>()
    private val userBudgetRepository = mockk<UserBudgetRepository>()
    private val reactiveSseService = mockk<ReactiveSseService>()
    private val guard = StreamAccessGuard(userRepository, userBudgetRepository, reactiveSseService)

    private fun given(version: Int?, member: Boolean) {
        every { reactiveSseService.sessionsEnded(EMAIL) } returns Mono.never()
        every { userRepository.findTokenVersion(EMAIL) } returns version
        every { userBudgetRepository.existsById(UserBudgetId(BUDGET_ID, EMAIL)) } returns member
    }

    @Test
    fun `stays silent while the session and the membership hold`() {
        given(version = 0, member = true)

        StepVerifier.create(guard.accessEnded(BUDGET_ID, EMAIL, 0, CHECK_EVERY))
            .expectSubscription()
            .expectNoEvent(CHECK_EVERY.multipliedBy(5))
            .thenCancel()
            .verify(TIMEOUT)
    }

    @Test
    fun `fires at the next check once the session was ended`() {
        given(version = 1, member = true)

        StepVerifier.create(guard.accessEnded(BUDGET_ID, EMAIL, 0, CHECK_EVERY))
            .expectNextCount(1)
            .thenCancel()
            .verify(TIMEOUT)
    }

    @Test
    fun `fires at the next check once the user is no longer a member`() {
        given(version = 0, member = false)

        StepVerifier.create(guard.accessEnded(BUDGET_ID, EMAIL, 0, CHECK_EVERY))
            .expectNextCount(1)
            .thenCancel()
            .verify(TIMEOUT)
    }

    @Test
    fun `fires when the account no longer exists`() {
        given(version = null, member = false)

        StepVerifier.create(guard.accessEnded(BUDGET_ID, EMAIL, 0, CHECK_EVERY))
            .expectNextCount(1)
            .thenCancel()
            .verify(TIMEOUT)
    }

    @Test
    fun `fires when the check itself fails`() {
        every { reactiveSseService.sessionsEnded(EMAIL) } returns Mono.never()
        every { userRepository.findTokenVersion(EMAIL) } throws IllegalStateException("database down")

        StepVerifier.create(guard.accessEnded(BUDGET_ID, EMAIL, 0, CHECK_EVERY))
            .expectNextCount(1)
            .thenCancel()
            .verify(TIMEOUT)
    }

    @Test
    fun `fires at once when the account's sessions are ended`() {
        given(version = 0, member = true)
        every { reactiveSseService.sessionsEnded(EMAIL) } returns Mono.just(EMAIL)

        StepVerifier.create(guard.accessEnded(BUDGET_ID, EMAIL, 0, Duration.ofHours(1)).take(1))
            .expectNext(EMAIL)
            .verifyComplete()
    }

    private companion object {
        const val BUDGET_ID = 1L
        const val EMAIL = "user@example.com"
        val CHECK_EVERY: Duration = Duration.ofMillis(50)
        val TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
