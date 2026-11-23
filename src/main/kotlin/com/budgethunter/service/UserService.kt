package com.budgethunter.service

import com.budgethunter.dto.CurrentUserResponse
import com.budgethunter.dto.GoogleSignInRequest
import com.budgethunter.dto.PrivacyPolicyStatus
import com.budgethunter.dto.RefreshTokenRequest
import com.budgethunter.dto.SetPasswordRequest
import com.budgethunter.dto.SignInRequest
import com.budgethunter.dto.SignInResponse
import com.budgethunter.dto.SignUpRequest
import com.budgethunter.dto.UserResponse
import com.budgethunter.exception.DuplicateEmailException
import com.budgethunter.model.AuthProvider
import com.budgethunter.model.User
import com.budgethunter.privacy.currentPolicyVersionOrNull
import com.budgethunter.privacy.privacyPolicyNow
import com.budgethunter.repository.PrivacyPolicyAcceptanceRepository
import com.budgethunter.repository.UserRepository
import com.budgethunter.util.GoogleTokenVerifier
import com.budgethunter.util.JwtUtil
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
@Suppress("LongParameterList") // Spring constructor injection; the clock is only overridden by tests.
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtUtil: JwtUtil,
    private val googleTokenVerifier: GoogleTokenVerifier,
    private val privacyPolicyAcceptanceRepository: PrivacyPolicyAcceptanceRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock = Clock.systemUTC()
) {

    private val logger = LoggerFactory.getLogger(UserService::class.java)

    @Transactional
    fun signUp(request: SignUpRequest): UserResponse {
        // Fast path for the common case. It is not atomic, so the insert below is what actually
        // guarantees a concurrent sign-up can't overwrite an account (see UserInsertRepository).
        if (userRepository.existsByEmail(request.email)) throw DuplicateEmailException()

        val acceptedVersion = currentPolicyVersionOrNull(request.acceptedPrivacyPolicyVersion)
        val user = User(
            email = request.email,
            name = request.name,
            password = passwordEncoder.encode(request.password),
            authProvider = AuthProvider.PASSWORD,
            privacyPolicyVersion = acceptedVersion,
            privacyPolicyAcceptedAt = acceptedVersion?.let { privacyPolicyNow(clock) }
        )

        val savedUser = insertNewUser(user) { DuplicateEmailException() }

        return UserResponse(
            email = savedUser.email,
            name = savedUser.name
        )
    }

    @Transactional
    fun signIn(request: SignInRequest): SignInResponse {
        val user = userRepository.findById(request.email)
            .orElseThrow { BadCredentialsException("Invalid email or password") }

        // A Google-only account has no password to compare against. Without this guard the
        // encoder would be handed a null hash and the request would fail as a 500 instead of
        // telling the caller their credentials are wrong.
        val storedPassword = user.password
            ?: throw BadCredentialsException("Invalid email or password")

        if (!passwordEncoder.matches(request.password, storedPassword)) {
            throw BadCredentialsException("Invalid email or password")
        }

        return issueSession(user)
    }

    @Transactional
    fun refreshToken(request: RefreshTokenRequest): SignInResponse {
        val user = userRepository.findByRefreshToken(request.refreshToken)
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
     *
     * Linking an account that had a password but no Google subject yet also removes that password and ends every
     * session it had (see [linkGoogle]); the response says so in `passwordRemoved`.
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
        var passwordRemoved = false
        val acceptedVersion = currentPolicyVersionOrNull(request.acceptedPrivacyPolicyVersion)

        val existingUser = userRepository.findByGoogleSubject(info.subject).orElse(null)
            ?.also { existing ->
                if (existing.email != email) {
                    // The email is the primary key and user_budgets / budget_entries point at it
                    // by foreign key, so rewriting it here would be a data migration rather than
                    // an update. Matching on the subject already kept them on their own account.
                    logger.info("Google account changed its email; keeping the stored address")
                }
            }
            // Locked, so a password being set on the account at this moment waits for the link (see setPassword).
            ?: userRepository.lockForUpdate(email)?.also { existing ->
                passwordRemoved = linkGoogle(existing, info.subject)
            }
        // An existing account records the acceptance only if it lacks this version (the update's own condition), so
        // the date of its first acceptance stays. Done before issueSession's save, which flushes the link above too.
        // existingUser keeps the privacy fields it was read with (the update bypasses it); nothing here reads them,
        // and the save cannot write them back (updatable = false).
        if (existingUser != null && acceptedVersion != null) {
            privacyPolicyAcceptanceRepository.recordAcceptance(existingUser.email, acceptedVersion, privacyPolicyNow(clock))
        }
        val user = existingUser
            ?: insertNewUser(
                User(
                    email = email,
                    // name is NOT NULL in the database and the claim is optional on an ID token.
                    name = info.name?.takeIf { it.isNotBlank() } ?: email.substringBefore('@'),
                    password = null,
                    googleSubject = info.subject,
                    authProvider = AuthProvider.GOOGLE,
                    privacyPolicyVersion = acceptedVersion,
                    privacyPolicyAcceptedAt = acceptedVersion?.let { privacyPolicyNow(clock) }
                )
            ) {
                // Another request created this account between our lookup and the insert. A
                // retry will find it and link it instead of wiping its password with a merge.
                DuplicateEmailException("Account was created by another request, please try again")
            }

        if (passwordRemoved) {
            // Closes the account's open event streams once the link is committed (ReactiveSseService); its access
            // and refresh tokens already stopped working with the version raised above.
            eventPublisher.publishEvent(SessionsEndedEvent(user.email))
            logger.info("Linked Google to an account with a password of unknown origin: password removed, sessions ended")
        }
        return issueSession(user, passwordRemoved)
    }

    /**
     * Links the Google account [subject] to [user], found by its email. Returns whether its password was removed.
     *
     * Sign-up with a password never proves who owns the email, so an account that had a password and no Google
     * subject may have been opened by someone else with the victim's address, waiting for the victim to sign in
     * with Google and fill it ("pre-account hijacking"). Google has just proven who controls the email; the
     * password may be anybody's. So it is removed, the account becomes GOOGLE, and [User.tokenVersion] is raised,
     * which ends every access token issued before (the refresh token is replaced by the new session anyway). The
     * owner can set a password again without the old one (setPassword, `POST /api/users/password`).
     *
     * An account that already had a Google subject only changes subject: its password, if any, was set while
     * signed in with Google (setPassword). An account without a password is linked as before.
     */
    private fun linkGoogle(user: User, subject: String): Boolean {
        val passwordOfUnknownOrigin = user.googleSubject == null && user.password != null
        user.googleSubject = subject
        if (passwordOfUnknownOrigin) {
            user.password = null
            user.tokenVersion += 1
        }
        user.authProvider = if (user.password != null) AuthProvider.PASSWORD_AND_GOOGLE else AuthProvider.GOOGLE
        return passwordOfUnknownOrigin
    }

    fun getCurrentUser(email: String): CurrentUserResponse =
        userRepository.findById(email)
            .orElseThrow { BadCredentialsException("User not found") }
            .let {
                CurrentUserResponse(
                    email = it.email,
                    name = it.name,
                    hasPassword = it.password != null,
                    authProvider = it.authProvider,
                    privacyPolicy = PrivacyPolicyStatus(
                        acceptedVersion = it.privacyPolicyVersion,
                        acceptedAt = it.privacyPolicyAcceptedAt
                    )
                )
            }

    /**
     * Sets or replaces the account password.
     *
     * When the account has no password yet no current one is required — the caller is already
     * authenticated by their bearer token, and this is how a user who signed up through Google
     * gains the ability to sign in with a password too.
     *
     * [tokenVersion] is the version of the caller's access token. It is checked again under the row lock: a request
     * that passed the JWT filter just before a Google link ended its sessions would otherwise find the password
     * gone and set one of its own without knowing any.
     */
    @Transactional
    fun setPassword(email: String, request: SetPasswordRequest, tokenVersion: Int) {
        val user = userRepository.lockForUpdate(email)
            ?: throw BadCredentialsException("User not found")
        if (user.tokenVersion != tokenVersion) throw BadCredentialsException("Session has ended")

        user.password?.let { requireCurrentPassword(request.currentPassword, it) }

        user.password = passwordEncoder.encode(request.newPassword)
        user.authProvider = if (user.googleSubject != null) {
            AuthProvider.PASSWORD_AND_GOOGLE
        } else {
            AuthProvider.PASSWORD
        }
        userRepository.save(user)
    }

    private fun requireCurrentPassword(currentPassword: String?, storedPassword: String) {
        if (currentPassword == null) throw BadCredentialsException("Current password is required")
        if (!passwordEncoder.matches(currentPassword, storedPassword)) {
            throw BadCredentialsException("Current password is incorrect")
        }
    }

    /**
     * Creates [user] with an `INSERT`, never a merge. A primary key or unique clash means the
     * account already exists (typically a concurrent request won the race) and becomes [onConflict].
     */
    private fun insertNewUser(user: User, onConflict: () -> DuplicateEmailException): User =
        try {
            userRepository.insert(user)
        } catch (e: DataIntegrityViolationException) {
            // The failed flush leaves the session and transaction unusable: throw right away and
            // let @Transactional roll back. Never keep using the repository after this catch.
            logger.warn("Account creation lost a race on an existing email: {}", e.javaClass.simpleName)
            // The root cause names the violated constraint, to tell a real email clash from an
            // unexpected one; debug only, since it can include the email.
            logger.debug("Account creation constraint violation", e.mostSpecificCause)
            throw onConflict()
        }

    /**
     * Issues a fresh access token and rotates the refresh token.
     *
     * Shared by every sign-in path so they cannot drift apart in what a session looks like.
     */
    private fun issueSession(user: User, passwordRemoved: Boolean = false): SignInResponse {
        val authToken = jwtUtil.generateToken(user.email, user.tokenVersion)
        val refreshToken = jwtUtil.generateRefreshToken()

        user.refreshToken = refreshToken
        user.refreshTokenExpiry = jwtUtil.getRefreshTokenExpiry()
        val savedUser = userRepository.save(user)

        return SignInResponse(
            authToken = authToken,
            refreshToken = refreshToken,
            email = savedUser.email,
            name = savedUser.name,
            passwordRemoved = passwordRemoved
        )
    }
}

/** Every session of the account [email] has been ended; published inside the transaction that ended them. */
data class SessionsEndedEvent(val email: String)
