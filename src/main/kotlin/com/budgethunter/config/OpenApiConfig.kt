package com.budgethunter.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun customOpenAPI(): OpenAPI {
        val securitySchemeName = "bearerAuth"

        return OpenAPI()
            .info(
                Info()
                    .title("BudgetHunter API")
                    .description("""
                        RESTful API for collaborative budget tracking and management.

                        ## Features
                        - User authentication with JWT tokens
                        - Collaborative budget management
                        - Real-time budget entry updates via Server-Sent Events (SSE)
                        - Budget entry tracking (income and expenses)
                        - Multi-user collaboration on shared budgets

                        ## Authentication
                        Most endpoints require authentication. Use the `/api/users/sign_in` endpoint to obtain a JWT token,
                        then include it in the Authorization header as `Bearer <token>` for subsequent requests.
                    """.trimIndent())
                    .version("1.0.0")
                    .contact(
                        Contact()
                            .name("BudgetHunter Team")
                            .email("kevmeneses@gmail.com.com")
                    )
                    .license(
                        License()
                            .name("MIT License")
                            .url("https://opensource.org/licenses/MIT")
                    )
            )
            .servers(
                listOf(
                    Server()
                        .url("http://localhost:8080")
                        .description("Local Development Server")
                )
            )
            .addSecurityItem(SecurityRequirement().addList(securitySchemeName))
            .components(
                io.swagger.v3.oas.models.Components()
                    .addSecuritySchemes(
                        securitySchemeName,
                        SecurityScheme()
                            .name(securitySchemeName)
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                            .description("Enter your JWT token obtained from the sign-in endpoint")
                    )
            )
    }
}
