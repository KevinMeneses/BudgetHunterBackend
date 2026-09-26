package com.budgethunter.util

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Component

/**
 * The identity Google asserts for a signed-in user, reduced to the claims we act on.
 */
data class GoogleUserInfo(
    /** Google's `sub` claim: stable for the lifetime of the account, unlike the email. */
    val subject: String,
    val email: String,
    val emailVerified: Boolean,
    val name: String?,
)

/**
 * Verifies a Google ID token and extracts the identity it carries.
 *
 * Split into an interface so the service layer can be tested without reaching Google.
 */
interface GoogleTokenVerifier {
    /**
     * @throws BadCredentialsException if the token is malformed, expired, signed by someone other
     *   than Google, or issued for an audience that is not one of ours.
     */
    fun verify(idToken: String): GoogleUserInfo
}

@Component
class GoogleIdTokenVerifierAdapter(
    /**
     * Every OAuth client that may produce a token for us. Android's Credential Manager stamps the
     * *web* client id into `aud` (we pass it as `serverClientId`), while the iOS SDK stamps the
     * *iOS* client id, so this has to accept more than one value.
     */
    @Value("\${google.auth.client-ids:}") private val clientIds: List<String>,
) : GoogleTokenVerifier {
    private val logger = LoggerFactory.getLogger(GoogleIdTokenVerifierAdapter::class.java)

    // internal so a test can assert what Spring actually bound: the value arrives as one
    // comma-separated string from the environment, and every entry must survive the trip.
    internal val audiences: List<String> get() = clientIds.map(String::trim).filter(String::isNotEmpty)

    private val verifier: GoogleIdTokenVerifier by lazy {
        GoogleIdTokenVerifier
            .Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
            .setAudience(audiences)
            .build()
    }

    override fun verify(idToken: String): GoogleUserInfo {
        // Unconfigured rather than misconfigured: without an audience the verifier would accept
        // a token minted for any application at all, so refuse instead of degrading silently.
        if (audiences.isEmpty()) {
            logger.error("google.auth.client-ids is not set; refusing every Google sign in")
            throw BadCredentialsException("Google sign in is not configured")
        }

        // verify() returns null for a token we reject and throws for one we cannot even parse.
        // Both mean the same thing to the caller, so collapse them into one 401.
        val payload =
            try {
                verifier.verify(idToken)?.payload
            } catch (e: Exception) {
                logger.warn("Could not verify the Google ID token: ${e.message}")
                throw BadCredentialsException("Invalid Google ID token")
            } ?: throw BadCredentialsException("Invalid Google ID token")

        return GoogleUserInfo(
            subject = payload.subject,
            email = payload.email,
            emailVerified = payload.emailVerified == true,
            name = payload["name"] as? String,
        )
    }
}
