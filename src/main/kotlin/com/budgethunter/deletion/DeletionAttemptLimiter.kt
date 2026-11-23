package com.budgethunter.deletion

import com.budgethunter.util.PerAccountRateLimiter
import java.time.Duration
import java.util.Locale

/**
 * Attempts at the public deletion endpoints, always on (the general limiter only runs in production and allows
 * 100 requests a minute, far too many for a password check). Every attempt counts, right or wrong.
 *
 * - **Per IP**, both endpoints: stops one client from trying many accounts.
 * - **Per email**, the password endpoint: stops a password guess against one account from many IPs. That is the
 *   brute-force defence, and it does not depend on the IP being right.
 *
 * The flip side: anyone can spend an account's web attempts for an hour. The account is not touched, and the app
 * (Ajustes > Eliminar cuenta) and the contact email still work.
 */
class DeletionAttemptLimiter(
    perIp: Int,
    perEmail: Int,
    window: Duration
) {
    private val ipLimiter = PerAccountRateLimiter(perIp, window)
    private val emailLimiter = PerAccountRateLimiter(perEmail, window)

    /** Throws 429 `TOO_MANY_ATTEMPTS` when [ip] has used its attempts. */
    fun checkIp(ip: String) {
        if (!ipLimiter.tryAcquire(ip)) throw WebAccountDeletionException.tooManyAttempts()
    }

    /** Throws 429 `TOO_MANY_ATTEMPTS` when [email] (case and spaces ignored) has used its attempts. */
    fun checkEmail(email: String) {
        if (!emailLimiter.tryAcquire(email.trim().lowercase(Locale.ROOT))) {
            throw WebAccountDeletionException.tooManyAttempts()
        }
    }
}
