package com.budgethunter.categorization

import com.budgethunter.model.AiUsage
import com.budgethunter.model.AiUsageId
import com.budgethunter.model.Plan
import com.budgethunter.model.User
import com.budgethunter.repository.AiUsageRepository
import com.budgethunter.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class CategorizationQuotaServiceTest {

    private val email = "a@b.c"
    private val now = Instant.parse("2026-11-30T23:59:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val period = "2026-11"

    private lateinit var aiUsage: AiUsageRepository
    private lateinit var users: UserRepository
    private var used = 0

    private val service by lazy {
        CategorizationQuotaService(aiUsage, users, CategorizationAllowance(50, 1000), clock)
    }

    @BeforeEach
    fun setup() {
        used = 0
        aiUsage = mockk(relaxed = true)
        users = mockk()
        user(Plan.FREE)
        every { aiUsage.findById(AiUsageId(email, period)) } answers {
            Optional.of(AiUsage(AiUsageId(email, period), categorizedEntries = used))
        }
        // The database's conditional UPDATE: all or nothing, never past the limit.
        every { aiUsage.tryConsumeCategorizations(email, period, any(), any()) } answers {
            val amount = thirdArg<Int>()
            if (used + amount <= arg<Int>(3)) {
                used += amount
                1
            } else {
                0
            }
        }
    }

    private fun user(plan: Plan, expiresAt: Instant? = null) {
        every { users.findById(email) } returns
            Optional.of(User(email = email, name = "A", plan = plan, planExpiresAt = expiresAt))
    }

    @Test
    fun `takes everything asked for while enough is left`() {
        val reservation = service.reserve(email, 10)

        assertEquals(CategorizationQuotaService.Reservation(email, period, 10), reservation)
        assertEquals(10, used)
        verify { aiUsage.createIfMissing(email, period) }
    }

    @Test
    fun `takes only what is left when asked for more`() {
        used = 47

        assertEquals(3, service.reserve(email, 10).granted)
        assertEquals(50, used)
    }

    @Test
    fun `takes nothing once the limit is reached, without trying to take more`() {
        used = 50

        assertEquals(0, service.reserve(email, 10).granted)
        assertEquals(50, used)
    }

    @Test
    fun `takes nothing from an account already past a lower limit (a premium that lapsed)`() {
        used = 300

        assertEquals(0, service.reserve(email, 10).granted)
    }

    @Test
    fun `asking for nothing touches nothing`() {
        assertEquals(0, service.reserve(email, 0).granted)
        verify(exactly = 0) { aiUsage.createIfMissing(any(), any()) }
    }

    @Test
    fun `premium gets the fair-use limit`() {
        user(Plan.PREMIUM, now.plusSeconds(3600))

        assertEquals(1000, service.usage(email).categorizedEntriesLimit)
        assertEquals(200, service.reserve(email, 200).granted)
    }

    @Test
    fun `a premium past its expiry counts as FREE`() {
        user(Plan.PREMIUM, now.minusSeconds(1))
        used = 45

        val usage = service.usage(email)

        assertEquals("FREE", usage.plan)
        assertEquals(50, usage.categorizedEntriesLimit)
        assertEquals(5, usage.categorizedEntriesRemaining)
        assertEquals(5, service.reserve(email, 10).granted)
    }

    @Test
    fun `an unknown account counts as FREE`() {
        every { users.findById(email) } returns Optional.empty()

        assertEquals("FREE", service.usage(email).plan)
    }

    @Test
    fun `usage reports the month, what is left and when it starts over`() {
        used = 12

        val usage = service.usage(email)

        assertEquals(period, usage.period)
        assertEquals(12, usage.categorizedEntriesUsed)
        assertEquals(38, usage.categorizedEntriesRemaining)
        assertEquals("2026-12-01", usage.resetsOn)
    }

    @Test
    fun `usage of a month with no row is zero`() {
        every { aiUsage.findById(AiUsageId(email, period)) } returns Optional.empty()

        assertEquals(0, service.usage(email).categorizedEntriesUsed)
    }

    @Test
    fun `a run that loses a race asks again for what is left`() {
        used = 40
        var first = true
        every { aiUsage.tryConsumeCategorizations(email, period, any(), any()) } answers {
            if (first) {
                first = false
                used += 8 // another run of the same account took 8 in between
                0
            } else {
                used += thirdArg<Int>()
                1
            }
        }

        assertEquals(2, service.reserve(email, 10).granted)
        verify { aiUsage.tryConsumeCategorizations(email, period, 10, 50) }
        verify { aiUsage.tryConsumeCategorizations(email, period, 2, 50) }
    }

    @Test
    fun `gives up after a few lost races and takes nothing`() {
        every { aiUsage.tryConsumeCategorizations(email, period, any(), any()) } returns 0

        assertEquals(0, service.reserve(email, 10).granted)
        verify(exactly = 5) { aiUsage.tryConsumeCategorizations(email, period, any(), any()) }
    }

    @Test
    fun `a race to create the month's row is harmless`() {
        every { aiUsage.createIfMissing(email, period) } throws
            org.springframework.dao.DataIntegrityViolationException("duplicate key")

        assertEquals(10, service.reserve(email, 10).granted)
    }

    @Test
    fun `release gives back to the month the units were taken from`() {
        service.release(CategorizationQuotaService.Reservation(email, "2026-10", 4))
        service.release(CategorizationQuotaService.Reservation(email, period, 0))

        verify { aiUsage.releaseCategorizations(email, "2026-10", 4) }
        verify(exactly = 0) { aiUsage.releaseCategorizations(email, period, any()) }
    }

    @Test
    fun `the budget of a run takes and gives back for its account`() {
        val budget = service.budgetFor(email)
        budget.giveBack(3) // nothing taken yet: nothing to give back

        assertEquals(5, budget.take(5))
        budget.giveBack(2)

        verify(exactly = 1) { aiUsage.releaseCategorizations(email, period, 2) }
    }

    @Test
    fun `allowances cannot be negative`() {
        assertThrows<IllegalArgumentException> { CategorizationAllowance(-1, 10) }
        assertThrows<IllegalArgumentException> { CategorizationAllowance(1, -10) }
    }
}
