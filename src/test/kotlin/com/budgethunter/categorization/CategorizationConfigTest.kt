package com.budgethunter.categorization

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class CategorizationConfigTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration::class.java))
        .withBean(ObjectMapper::class.java, { ObjectMapper() })
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
    fun `can be switched off`() {
        runner.withPropertyValues("categorization.enabled=false").run { context ->
            assertEquals(0, context.getBeansOfType(CategoryClassifier::class.java).size)
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
}
