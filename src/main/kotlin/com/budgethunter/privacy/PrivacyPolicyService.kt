package com.budgethunter.privacy

import com.budgethunter.dto.PrivacyPolicyStatus
import com.budgethunter.repository.PrivacyPolicyAcceptanceRepository
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** `POST /api/users/me/privacy-policy`: the signed-in account accepts the privacy policy from the app. */
@Service
class PrivacyPolicyService(
    private val repository: PrivacyPolicyAcceptanceRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Records that [email] accepted [version]. Only the version in force can be accepted: anything else is an
     * `IllegalArgumentException` (400), since the app would be showing a text that is not the one in force.
     * Accepting it again keeps the first date.
     */
    @Transactional
    fun accept(email: String, version: String): PrivacyPolicyStatus {
        require(version == PRIVACY_POLICY_VERSION) {
            "Privacy policy version $version is not the current one ($PRIVACY_POLICY_VERSION)"
        }
        repository.recordAcceptance(email, version, privacyPolicyNow(clock))
        val stored = repository.findColumns(email) ?: throw BadCredentialsException("User not found")
        return PrivacyPolicyStatus(acceptedVersion = stored.version, acceptedAt = stored.acceptedAt)
    }
}
