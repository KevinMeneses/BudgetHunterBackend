package com.budgethunter.deletion

import com.budgethunter.dto.WebAccountDeletionResponse
import com.budgethunter.model.Plan
import com.budgethunter.model.User
import com.budgethunter.repository.UserRepository
import com.budgethunter.service.AccountDeletionService
import com.budgethunter.util.GoogleTokenVerifier
import com.budgethunter.util.GoogleUserInfo
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock
import java.time.Instant

/**
 * Account deletion from the public page `/eliminar-cuenta`, for people who no longer have the app. It proves who
 * the caller is the way the sign-in endpoints do (password like `sign_in`, Google ID token like
 * `sign_in_with_google`) and then runs exactly what `DELETE /api/users/me` runs: [AccountDeletionService].
 *
 * Not one transaction on purpose: the check reads, then [AccountDeletionService.deleteAccount] locks and deletes
 * in its own. An account deleted in between answers the same generic error.
 */
class WebAccountDeletionService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val googleTokenVerifier: GoogleTokenVerifier,
    private val accountDeletionService: AccountDeletionService,
    private val attemptLimiter: DeletionAttemptLimiter,
    private val clock: Clock = Clock.systemUTC()
) {

    private val logger = LoggerFactory.getLogger(WebAccountDeletionService::class.java)

    /** Compared against when there is no hash to compare, so an unknown email takes as long as a known one. */
    private val dummyHash: String by lazy { passwordEncoder.encode("budgethunter-no-account") }

    fun deleteWithCredentials(email: String, password: String, clientIp: String): WebAccountDeletionResponse {
        attemptLimiter.checkIp(clientIp)
        attemptLimiter.checkEmail(email)

        val user = userRepository.findById(email.trim()).orElse(null)
        val storedHash = user?.password
        // Always one bcrypt comparison: an unknown email, a Google-only account (no password) and a wrong password
        // all answer the same error after the same time.
        val matches = passwordEncoder.matches(password, storedHash ?: dummyHash)
        if (user == null || storedHash == null || !matches) throw WebAccountDeletionException.invalidCredentials()

        return delete(user, WebAccountDeletionException::invalidCredentials)
    }

    fun deleteWithGoogle(idToken: String, clientIp: String): WebAccountDeletionResponse {
        attemptLimiter.checkIp(clientIp)
        val info = verifiedGoogleIdentity(idToken)

        // The account sign_in_with_google would open with this token: the one linked to the Google subject, else
        // the one with the verified email (which that endpoint would link).
        val user = userRepository.findByGoogleSubject(info.subject).orElse(null)
            ?: userRepository.findById(info.email.lowercase()).orElse(null)
            ?: throw WebAccountDeletionException.noAccount()

        return delete(user, WebAccountDeletionException::noAccount)
    }

    private fun verifiedGoogleIdentity(idToken: String): GoogleUserInfo {
        val info = try {
            googleTokenVerifier.verify(idToken)
        } catch (e: BadCredentialsException) {
            throw WebAccountDeletionException.invalidGoogleToken(e)
        }
        // Same rule as sign_in_with_google: an unverified email proves nothing about the account it names.
        if (!info.emailVerified) throw WebAccountDeletionException.invalidGoogleToken()
        return info
    }

    private fun delete(user: User, onGone: (Throwable) -> WebAccountDeletionException): WebAccountDeletionResponse {
        val plan = user.planAt(Instant.now(clock))
        try {
            accountDeletionService.deleteAccount(user.email)
        } catch (e: BadCredentialsException) {
            // Deleted by another request since we read it.
            throw onGone(e)
        }
        // No email in the log (AccountDeletionService logs the counts).
        logger.info("Account deleted from the web page (plan {})", plan)
        return WebAccountDeletionResponse(deleted = true, plan = plan, cancelSubscriptionInStore = plan == Plan.PREMIUM)
    }
}
