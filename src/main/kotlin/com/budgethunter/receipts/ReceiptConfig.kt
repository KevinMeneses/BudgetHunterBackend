package com.budgethunter.receipts

import com.budgethunter.repository.AiUsageRepository
import com.budgethunter.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
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
 * Wires the receipt reading. Every setting has a default here, so the test `application.properties` needs no
 * mirroring; production overrides them from the environment (see application-production.properties).
 *
 * It shares `GEMINI_API_KEY` with categorization. Without a key every scan answers 503 AI_UNAVAILABLE (and the
 * scan is given back), so dev, tests and CI cannot reach Google by accident.
 */
@Configuration
@ConditionalOnProperty(prefix = "receipts", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ReceiptConfig {

    private val log = LoggerFactory.getLogger(ReceiptConfig::class.java)

    @Bean
    @Suppress("LongParameterList") // One @Value per setting; a properties class would only add indirection.
    fun receiptExtractor(
        restClientBuilder: RestClient.Builder,
        objectMapper: ObjectMapper,
        @Value("\${receipts.gemini.api-key:\${categorization.gemini.api-key:}}") apiKey: String,
        @Value("\${receipts.gemini.model:$DEFAULT_MODEL}") model: String,
        @Value("\${receipts.gemini.fallback-model:$DEFAULT_FALLBACK_MODEL}") fallbackModel: String,
        @Value("\${receipts.gemini.thinking-level:$DEFAULT_THINKING_LEVEL}") thinkingLevel: String,
        @Value("\${receipts.gemini.base-url:$DEFAULT_BASE_URL}") baseUrl: String,
        @Value("\${receipts.gemini.timeout-seconds:$DEFAULT_TIMEOUT_SECONDS}") timeoutSeconds: Long,
        @Value("\${receipts.gemini.max-requests-per-minute:${ReceiptGeminiSettings.DEFAULT_MAX_REQUESTS_PER_MINUTE}}")
        maxRequestsPerMinute: Int
    ): ReceiptExtractor {
        if (apiKey.isBlank()) log.info("Receipt reading has no GEMINI_API_KEY; every scan will answer AI_UNAVAILABLE")
        val timeout = Duration.ofSeconds(timeoutSeconds)
        val requestFactory = JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build())
            .apply { setReadTimeout(timeout) }
        val restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory).build()
        return GeminiReceiptExtractor(
            restClient,
            ReceiptGeminiSettings(
                apiKey = apiKey.trim(),
                model = model.trim(),
                maxRequestsPerMinute = maxRequestsPerMinute,
                fallbackModel = fallbackModel.trim(),
                thinkingLevel = thinkingLevel.trim()
            ),
            objectMapper
        )
    }

    @Bean
    fun receiptQuotaService(
        aiUsageRepository: AiUsageRepository,
        userRepository: UserRepository,
        @Value("\${receipts.free-monthly-scans:$DEFAULT_FREE_MONTHLY_SCANS}") freeMonthlyScans: Int,
        @Value("\${receipts.premium-monthly-scans:$DEFAULT_PREMIUM_MONTHLY_SCANS}") premiumMonthlyScans: Int
    ) = ReceiptQuotaService(
        aiUsageRepository,
        userRepository,
        ReceiptAllowance(freeMonthlyScans, premiumMonthlyScans)
    )

    @Bean
    @Suppress("LongParameterList") // One @Value per setting, like receiptExtractor above.
    fun receiptService(
        receiptExtractor: ReceiptExtractor,
        receiptQuotaService: ReceiptQuotaService,
        registry: MeterRegistry,
        @Value("\${receipts.max-file-bytes:$DEFAULT_MAX_FILE_BYTES}") maxFileBytes: Int,
        @Value("\${receipts.max-scans-per-user-per-minute:$DEFAULT_MAX_SCANS_PER_USER_PER_MINUTE}") perUserPerMinute: Int,
        @Value("\${receipts.max-concurrent-scans:$DEFAULT_MAX_CONCURRENT_SCANS}") maxConcurrentScans: Int
    ) = ReceiptService(
        receiptExtractor,
        receiptQuotaService,
        ReceiptFileValidator(maxFileBytes),
        ScanRateLimiter(perUserPerMinute),
        registry,
        maxConcurrentScans
    )

    private companion object {
        // Model ids come and go, so this is only a default (the one the app used to call).
        const val DEFAULT_MODEL = "gemini-3.8-flash"

        // Asked when the main model is overloaded (Gemini answers 503 "high demand" at peaks). A lighter model
        // that categorization already uses; set RECEIPTS_GEMINI_FALLBACK_MODEL empty to retry the main one instead.
        const val DEFAULT_FALLBACK_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_TIMEOUT_SECONDS = 30L

        // Gemini 3 models reason before answering by default, which on a receipt image could pass the timeout.
        const val DEFAULT_THINKING_LEVEL = "low"
        const val DEFAULT_FREE_MONTHLY_SCANS = 10
        const val DEFAULT_PREMIUM_MONTHLY_SCANS = 200

        // Well above a person scanning receipts by hand, well below what drains the server-wide budget.
        const val DEFAULT_MAX_SCANS_PER_USER_PER_MINUTE = 6

        // Scans in flight at once on this instance (each holds its file in memory and a request thread).
        const val DEFAULT_MAX_CONCURRENT_SCANS = 8

        // 12 MB raw, the size the app already caps PDFs at; Base64 makes it 16 MB on the wire.
        const val DEFAULT_MAX_FILE_BYTES = 12 * 1024 * 1024
    }
}
