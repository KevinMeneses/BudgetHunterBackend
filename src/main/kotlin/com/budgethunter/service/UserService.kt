package com.budgethunter.service

import com.budgethunter.dto.CurrentUserResponse
import com.budgethunter.dto.GoogleSignInRequest
import com.budgethunter.dto.RefreshTokenRequest
import com.budgethunter.dto.SetPasswordRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.dto.UserResponse
import com.budgethunter.model.AuthProvider
import com.budgethunter.model.User
import com.budgethunter.repository.UserRepository
import com.budgethunter.util.GoogleTokenVerifier
import com.budgethunter.util.JwtUtil
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtUtil: JwtUtil,
    private val googleTokenVerifier: GoogleTokenVerifier,
) {
    private val logger = LoggerFactory.getLogger(UserService::class.java)

    @Transactional
    fun signUp(request: SignUpRequest): UserResponse {
        if (userRepository.existsByEmail(request.email)) {
            throw IllegalArgumentException("Email already exists")
        }

        val user =
            User(
                email = request.email,
                name = request.name,
                password = passwordEncoder.encode(request.password),
                authProvider = AuthProvider.PASSWORD,
            )

        val savedUser = userRepository.save(user)

        return UserResponse(
            email = savedUser.email,
            name = savedUser.name,
        )
    }

    @Transactional
    fun signIn(request: SignInRequest): SignInResponse {
        val user =
            userRepository
                .findById(request.email)
                .orElseThrow { BadCredentialsException("Invalid email or password") }

        // A Google-only account has no password to compare against. Without this guard the
        // encoder would be handed a null hash and the request would fail as a 500 instead of
        // telling the caller their credentials are wrong.
        val storedPassword =
            user.password
                ?: throw BadCredentialsException("Invalid email or password")

        if (!passwordEncoder.matches(request.password, storedPassword)) {
            throw BadCredentialsException("Invalid email or password")
        }

        return issueSession(user)
    }

    @Transactional
    fun refreshToken(request: RefreshTokenRequest): SignInResponse {
        val user =
            userRepository
                .findByRefreshToken(request.refreshToken)
                .orElseThrow { BadCredentialsException("Invalid refresh token") }

        // Validate refresh token expiry
        if (user.refreshTokenExpiry == null || user.refreshTokenExpiry!!.isBefore(Instant.now())) {
            throw BadCredentialsException("Refresh token has expired")
        }

        return issueSession(user)
    }

    /**
     * Signs in with a Google ID token, creating the account on first use.
     *
     * An existing password account whose email matches is *linked* rather than rejected: Google
     * has already proven the user controls that address, so sending them back to a password they
     * may not remember would only lock them out of their own budgets.
     */
    @Transactional
    fun signInWithGoogle(request: GoogleSignInRequest): SignInResponse {
        val info = googleTokenVerifier.verify(request.idToken)

        // The whole linking scheme rests on this claim. A Workspace or third-party-domain account
        // can assert an address it does not actually control, and honouring that would hand over
        // someone else's budgets.
        if (!info.emailVerified) {
            throw BadCredentialsException("Google account email is not verified")
        }

        val email = info.email.lowercase()

        val user =
            userRepository
                .findByGoogleSubject(info.subject)
                .orElse(null)
                ?.also { existing ->
                    if (existing.email != email) {
                        // The email is the primary key and user_budgets / budget_entries point at it
                        // by foreign key, so rewriting it here would be a data migration rather than
                        // an update. Matching on the subject already kept them on their own account.
                        logger.info("Google account changed its email; keeping the stored address")
                    }
                }
                ?: userRepository.findById(email).orElse(null)?.also { existing ->
                    existing.googleSubject = info.subject
                    existing.authProvider =
                        if (existing.password != null) {
                            AuthProvider.PASSWORD_AND_GOOGLE
                        } else {
                            AuthProvider.GOOGLE
                        }
                }
                ?: User(
                    email = email,
                    // name is NOT NULL in the database and the claim is optional on an ID token.
                    name = info.name?.takeIf { it.isNotBlank() } ?: email.substringBefore('@'),
                    password = null,
                    googleSubject = info.subject,
                    authProvider = AuthProvider.GOOGLE,
                )

        return issueSession(user)
    }

    fun getCurrentUser(email: String): CurrentUserResponse =
        userRepository
            .findById(email)
            .orElseThrow { BadCredentialsException("User not found") }
            .let {
                CurrentUserResponse(
                    email = it.email,
                    name = it.name,
                    hasPassword = it.password != null,
                    authProvider = it.authProvider,
                )
            }

    /**
     * Sets or replaces the account password.
     *
     * When the account has no password yet no current one is required — the caller is already
     * authenticated by their bearer token, and this is how a user who signed up through Google
     * gains the ability to sign in with a password too.
     */
    @Transactional
    fun setPassword(
        email: String,
        request: SetPasswordRequest,
    ) {
        val user =
            userRepository
                .findById(email)
                .orElseThrow { BadCredentialsException("User not found") }

        user.password?.let { storedPassword ->
            val currentPassword =
                request.currentPassword
                    ?: throw BadCredentialsException("Current password is required")
            if (!passwordEncoder.matches(currentPassword, storedPassword)) {
                throw BadCredentialsException("Current password is incorrect")
            }
        }

        user.password = passwordEncoder.encode(request.newPassword)
        user.authProvider =
            if (user.googleSubject != null) {
                AuthProvider.PASSWORD_AND_GOOGLE
            } else {
                AuthProvider.PASSWORD
            }
        userRepository.save(user)
    }

    /**
     * Issues a fresh access token and rotates the refresh token.
     *
     * Shared by every sign-in path so they cannot drift apart in what a session looks like.
     */
    private fun issueSession(user: User): SignInResponse {
        val authToken = jwtUtil.generateToken(user.email)
        val refreshToken = jwtUtil.generateRefreshToken()

        user.refreshToken = refreshToken
        user.refreshTokenExpiry = jwtUtil.getRefreshTokenExpiry()
        val savedUser = userRepository.save(user)

        return SignInResponse(
            authToken = authToken,
            refreshToken = refreshToken,
            email = savedUser.email,
            name = savedUser.name,
        )
    }
}
