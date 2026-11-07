package com.budgethunter.billing

import com.budgethunter.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * Wires the store purchases (RevenueCat). Every setting has a default here, so the test `application.properties`
 * needs no mirroring; production reads them from the environment (see application-production.properties).
 *
 * Without `REVENUECAT_SECRET_API_KEY` nothing reaches RevenueCat (sync and webhooks answer 503); without
 * `REVENUECAT_WEBHOOK_AUTHORIZATION` every webhook is refused. Dev, tests and CI have neither.
 */
@Configuration
class BillingConfig {

    private val log = LoggerFactory.getLogger(BillingConfig::class.java)

    @Bean
    fun subscriberLookup(
        restClientBuilder: RestClient.Builder,
        @Value("\${billing.revenuecat.secret-api-key:}") secretApiKey: String,
        @Value("\${billing.revenuecat.entitlement-id:$DEFAULT_ENTITLEMENT_ID}") entitlementId: String,
        @Value("\${billing.revenuecat.base-url:$DEFAULT_BASE_URL}") baseUrl: String
    ): SubscriberLookup {
        if (secretApiKey.isBlank()) log.info("No REVENUECAT_SECRET_API_KEY: plans will not follow store purchases")
        val timeout = Duration.ofSeconds(TIMEOUT_SECONDS)
        val httpClient = HttpClient.newBuilder().connectTimeout(timeout).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(timeout) }
        val restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory).build()
        return RevenueCatClient(restClient, secretApiKey.trim(), entitlementId.trim())
    }

    @Bean
    fun billingService(userRepository: UserRepository, subscriberLookup: SubscriberLookup) =
        BillingService(userRepository, subscriberLookup)

    @Bean
    fun webhookAuthorization(
        @Value("\${billing.revenuecat.webhook-authorization:}") authorization: String
    ): WebhookAuthorization {
        if (authorization.isBlank()) log.info("No REVENUECAT_WEBHOOK_AUTHORIZATION: RevenueCat webhooks are refused")
        return WebhookAuthorization(authorization.trim())
    }

    private companion object {
        // The entitlement the app's products unlock, as named in the RevenueCat dashboard.
        const val DEFAULT_ENTITLEMENT_ID = "premium"
        const val DEFAULT_BASE_URL = "https://api.revenuecat.com"

        // Per call to RevenueCat (its API answers in well under a second). A webhook makes up to
        // BillingService's 5 calls in a row, so this keeps the whole event around 25 s at worst.
        const val TIMEOUT_SECONDS = 5L
    }
}
