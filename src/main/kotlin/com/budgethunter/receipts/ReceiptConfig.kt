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
        require(timeoutSeconds > 0) { "receipts.gemini.timeout-seconds must be positive" }
        if (apiKey.isBlank()) log.info("Receipt reading has no GEMINI_API_KEY; every scan will answer AI_UNAVAILABLE")
        val timeout = Duration.ofSeconds(timeoutSeconds)
        // HTTP/1.1: added while scans timed out at exactly 30 s, in case large bodies stalled on the JDK client's
        // HTTP/2. The cause turned out to be the overloaded main model (see DEFAULT_MODEL); this stays because one
        // request per call loses nothing that HTTP/2 multiplexing would have given here.
        val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(timeout)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
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
        // Model ids come and go, so these are only defaults; see Google's deprecations page before changing them.
        // Measured from the production droplet (Nov 2026, three calls each): 3.5-flash-lite answered 200 in ~0.6 s
        // every time; 3.6-flash 200 in 6-20 s; 3.8-flash (the one the app used to call) 503 "high demand" every
        // time, once after 44 s, which is what made scans time out at 30 s. Older ids (3.5-flash, 3.7-flash) now
        // route to 3.6/3.8, and the 2.5 ones are retiring or do not accept thinkingLevel.
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

        // Asked when the main model fails: a full Flash model, slower but stable and better on hard receipts.
        // Set RECEIPTS_GEMINI_FALLBACK_MODEL empty to retry the main one instead.
        const val DEFAULT_FALLBACK_MODEL = "gemini-3.6-flash"
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
