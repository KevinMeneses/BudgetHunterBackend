package com.budgethunter.repository

import com.budgethunter.model.User
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * The two privacy policy columns of `users`, which nothing else writes once the account exists (they are not
 * updatable through the entity; see [User.privacyPolicyVersion]).
 */
interface PrivacyPolicyAcceptanceRepository : Repository<User, String> {

    /**
     * Records that the account accepted privacy policy [version] at [acceptedAt], unless it already had: the first
     * acceptance of a version is the proof that counts, so accepting it again keeps that date. Returns the rows
     * changed: 0 when the account had already accepted [version], or does not exist.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        "UPDATE User u SET u.privacyPolicyVersion = :version, u.privacyPolicyAcceptedAt = :acceptedAt " +
            "WHERE u.email = :email AND (u.privacyPolicyVersion IS NULL OR u.privacyPolicyVersion <> :version)"
    )
    fun recordAcceptance(
        @Param("email") email: String,
        @Param("version") version: String,
        @Param("acceptedAt") acceptedAt: Instant
    ): Int

    /**
     * The columns as they are in the database now, after [recordAcceptance] (an entity already in the persistence
     * context would still have what it was read with). Null when there is no such account.
     */
    @Query(
        "SELECT u.privacyPolicyVersion AS version, u.privacyPolicyAcceptedAt AS acceptedAt FROM User u " +
            "WHERE u.email = :email"
    )
    fun findColumns(@Param("email") email: String): PrivacyPolicyColumns?
}

/** Projection of [PrivacyPolicyAcceptanceRepository.findColumns]. */
interface PrivacyPolicyColumns {
    val version: String?
    val acceptedAt: Instant?
}
