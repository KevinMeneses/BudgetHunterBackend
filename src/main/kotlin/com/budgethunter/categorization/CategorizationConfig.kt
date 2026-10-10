package com.budgethunter.categorization

import com.budgethunter.repository.BudgetEntryRepository
import com.budgethunter.repository.BudgetRepository
import com.budgethunter.repository.UserRepository
import com.budgethunter.service.BudgetService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * Wires [GeminiCategoryClassifier]. Everything has a default in code, like the rest of this
 * app's `@Value` settings, and production overrides them from the environment.
 *
 * On unless `categorization.enabled=false`, but with no `GEMINI_API_KEY` the classifier never
 * sends anything, so dev, tests and CI cannot reach Google by accident.
 */
@Configuration
@ConditionalOnProperty(prefix = "categorization", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class CategorizationConfig {

    private val log = LoggerFactory.getLogger(CategorizationConfig::class.java)

    @Bean
    @Suppress("LongParameterList") // One @Value per setting; a properties class would only add indirection.
    fun geminiCategoryClassifier(
        restClientBuilder: RestClient.Builder,
        objectMapper: ObjectMapper,
        @Value("\${categorization.gemini.api-key:}") apiKey: String,
        @Value("\${categorization.gemini.model:$DEFAULT_MODEL}") model: String,
        @Value("\${categorization.gemini.base-url:$DEFAULT_BASE_URL}") baseUrl: String,
        @Value("\${categorization.gemini.timeout-seconds:$DEFAULT_TIMEOUT_SECONDS}") timeoutSeconds: Long,
        @Value("\${categorization.gemini.max-requests-per-minute:${GeminiSettings.DEFAULT_MAX_REQUESTS_PER_MINUTE}}")
        maxRequestsPerMinute: Int,
        @Value("\${categorization.gemini.max-batch-size:${GeminiSettings.DEFAULT_MAX_BATCH_SIZE}}")
        maxBatchSize: Int
    ): GeminiCategoryClassifier {
        if (apiKey.isBlank()) {
            log.info("Automatic categorization is enabled but GEMINI_API_KEY is empty; it will stay idle")
        }
        val timeout = Duration.ofSeconds(timeoutSeconds)
        val requestFactory = JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build())
            .apply { setReadTimeout(timeout) }
        val restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory).build()
        return GeminiCategoryClassifier(
            restClient,
            GeminiSettings(apiKey.trim(), model, maxRequestsPerMinute, maxBatchSize),
            objectMapper
        )
    }

    @Bean
    fun categoryCache() = CategoryCache()

    /** Rules first, then the cache, then Gemini (which stays idle without a key). */
    @Bean
    fun categoryResolver(cache: CategoryCache, gemini: GeminiCategoryClassifier) =
        CategoryResolver(RuleBasedClassifier(), cache, gemini)

    @Bean
    fun budgetCategorizationService(
        resolver: CategoryResolver,
        budgetService: BudgetService,
        budgetRepository: BudgetRepository,
        budgetEntryRepository: BudgetEntryRepository,
        userRepository: UserRepository
    ) = BudgetCategorizationService(resolver, budgetService, budgetRepository, budgetEntryRepository, userRepository)

    private companion object {
        // Model ids come and go (the 2.5 family is being retired), so this is only a default.
        const val DEFAULT_MODEL = "gemini-3.1-flash-lite"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_TIMEOUT_SECONDS = 10L
    }
}
