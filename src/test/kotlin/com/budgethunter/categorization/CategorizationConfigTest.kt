package com.budgethunter.categorization

import com.budgethunter.repository.AiUsageRepository
import com.budgethunter.repository.BudgetEntryRepository
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.UserRepository
import com.budgethunter.service.BudgetService
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.Optional

class CategorizationConfigTest {

    // An unknown account with no usage this month: FREE, nothing used.
    private val users = mockk<UserRepository> { every { findById(any()) } returns Optional.empty() }
    private val aiUsage = mockk<AiUsageRepository> { every { findById(any()) } returns Optional.empty() }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration::class.java))
        .withBean(ObjectMapper::class.java, { ObjectMapper() })
        .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
        .withBean(BudgetEntryRepository::class.java, { mockk<BudgetEntryRepository>() })
        .withBean(BudgetRepository::class.java, { mockk<BudgetRepository>() })
        .withBean(UserRepository::class.java, { users })
        .withBean(AiUsageRepository::class.java, { aiUsage })
        .withBean(BudgetService::class.java, { mockk<BudgetService>() })
        .withUserConfiguration(CategorizationConfig::class.java)

    @Test
    fun `is on by default and idle without a key`() {
        runner.run { context ->
            val classifier = context.getBean(GeminiCategoryClassifier::class.java)
            // No key: nothing is sent (no server is bound here, so a request would throw), and it answers null.
            assertEquals(listOf<String?>(null), classifier.classify(listOf("netflix")))
        }
    }

    @Test
    fun `wires the resolver and the service`() {
        runner.run { context ->
            assertEquals(1, context.getBeansOfType(CategoryResolver::class.java).size)
            assertEquals(1, context.getBeansOfType(BudgetCategorizationService::class.java).size)
        }
    }

    @Test
    fun `can be switched off`() {
        runner.withPropertyValues("categorization.enabled=false").run { context ->
            assertEquals(0, context.getBeansOfType(CategoryClassifier::class.java).size)
            assertEquals(0, context.getBeansOfType(BudgetCategorizationService::class.java).size)
        }
    }

    @Test
    fun `reads its settings from properties`() {
        runner.withPropertyValues(
            "categorization.gemini.api-key=k",
            "categorization.gemini.model=m",
            "categorization.gemini.base-url=http://localhost:1",
            "categorization.gemini.timeout-seconds=1"
        ).run { context ->
            // Key present, endpoint unreachable: it must still answer null rather than throw.
            assertEquals(listOf<String?>(null), context.getBean(GeminiCategoryClassifier::class.java).classify(listOf("netflix")))
        }
    }

    @Test
    fun `charges what Gemini is asked against 50 a month for FREE by default`() {
        runner.run { context ->
            val usage = context.getBean(CategorizationQuotaService::class.java).usage("a@b.c")
            assertEquals("FREE", usage.plan)
            assertEquals(50, usage.categorizedEntriesLimit)
            assertEquals(50, usage.categorizedEntriesRemaining)
        }
    }

    @Test
    fun `reads the monthly allowances from properties`() {
        runner.withPropertyValues("categorization.free-monthly-entries=7").run { context ->
            assertEquals(7, context.getBean(CategorizationQuotaService::class.java).usage("a@b.c").categorizedEntriesLimit)
        }
    }
}
