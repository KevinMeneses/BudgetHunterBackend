package com.budgethunter.deletion

/**
 * A web deletion that did not happen. [code] is one of [INVALID_CREDENTIALS] (401, the same for an unknown
 * email, a wrong password and an account without a password, so the page never tells whether an email has an
 * account), [INVALID_GOOGLE_TOKEN] (401), [NO_ACCOUNT] (404, only after Google proved who the caller is) or
 * [TOO_MANY_ATTEMPTS] (429).
 */
class WebAccountDeletionException(
    val status: Int,
    val code: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {

    companion object {
        const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
        const val INVALID_GOOGLE_TOKEN = "INVALID_GOOGLE_TOKEN"
        const val NO_ACCOUNT = "NO_ACCOUNT"
        const val TOO_MANY_ATTEMPTS = "TOO_MANY_ATTEMPTS"

        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
        private const val TOO_MANY_REQUESTS = 429

        fun invalidCredentials(cause: Throwable? = null) =
            WebAccountDeletionException(UNAUTHORIZED, INVALID_CREDENTIALS, "Invalid email or password", cause)

        fun invalidGoogleToken(cause: Throwable? = null) =
            WebAccountDeletionException(UNAUTHORIZED, INVALID_GOOGLE_TOKEN, "Invalid Google sign in", cause)

        fun noAccount(cause: Throwable? = null) =
            WebAccountDeletionException(NOT_FOUND, NO_ACCOUNT, "No account for this Google account", cause)

        fun tooManyAttempts() =
            WebAccountDeletionException(TOO_MANY_REQUESTS, TOO_MANY_ATTEMPTS, "Too many attempts; try again later")
    }
}
