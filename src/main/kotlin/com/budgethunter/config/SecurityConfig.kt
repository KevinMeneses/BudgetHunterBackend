package com.budgethunter.config

import com.budgethunter.filter.JwtAuthenticationFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val customAuthenticationEntryPoint: CustomAuthenticationEntryPoint
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder()
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/api/users/sign_up",
                        "/api/users/sign_in",
                        "/api/users/refresh_token",
                        "/api/users/sign_in_with_google",
                        // Authenticated by its own shared secret (RevenueCatWebhookController), not a JWT.
                        "/api/webhooks/revenuecat",
                        // The public account deletion page: each request proves who it is (password or
                        // Google ID token) in its own body. WebAccountDeletionController.
                        "/api/users/delete_with_credentials",
                        "/api/users/delete_with_google",
                        "/api/users/delete_account/config",
                        // Public pages (static files): the privacy policy and the account deletion page
                        "/privacidad",
                        "/privacidad.html",
                        "/eliminar-cuenta",
                        "/eliminar-cuenta.html",
                        "/h2-console/**",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/actuator/health/**",
                        "/actuator/health"
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { exceptionHandling ->
                exceptionHandling.authenticationEntryPoint(customAuthenticationEntryPoint)
            }
            .headers { headers ->
                headers.frameOptions { it.sameOrigin() } // For H2 console
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }
}
