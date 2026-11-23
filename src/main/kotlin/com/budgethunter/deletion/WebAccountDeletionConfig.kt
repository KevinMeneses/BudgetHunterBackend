package com.budgethunter.deletion

import com.budgethunter.repository.UserRepository
import com.budgethunter.service.AccountDeletionService
import com.budgethunter.util.GoogleTokenVerifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Duration

/**
 * Wires the deletion from the public page `/eliminar-cuenta`. Defaults live here, so the test
 * `application.properties` needs no mirroring; production reads `ACCOUNT_DELETION_MAX_ATTEMPTS_PER_IP` and
 * `ACCOUNT_DELETION_MAX_ATTEMPTS_PER_EMAIL` (attempts per hour).
 */
@Configuration
class WebAccountDeletionConfig {

    @Bean
    fun deletionAttemptLimiter(
        @Value("\${account-deletion.max-attempts-per-ip:$DEFAULT_PER_IP}") perIp: Int,
        @Value("\${account-deletion.max-attempts-per-email:$DEFAULT_PER_EMAIL}") perEmail: Int
    ) = DeletionAttemptLimiter(perIp, perEmail, Duration.ofHours(1))

    @Bean
    fun webAccountDeletionService(
        userRepository: UserRepository,
        passwordEncoder: PasswordEncoder,
        googleTokenVerifier: GoogleTokenVerifier,
        accountDeletionService: AccountDeletionService,
        deletionAttemptLimiter: DeletionAttemptLimiter
    ) = WebAccountDeletionService(
        userRepository,
        passwordEncoder,
        googleTokenVerifier,
        accountDeletionService,
        deletionAttemptLimiter
    )

    private companion object {
        // A person who mistypes a few times is never stopped; a guesser gets 5 passwords an hour per account.
        const val DEFAULT_PER_IP = 20
        const val DEFAULT_PER_EMAIL = 5
    }
}
