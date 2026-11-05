package com.budgethunter.repository

import com.budgethunter.model.User
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext

/**
 * Creates accounts with a guaranteed `INSERT`.
 *
 * [User] has an assigned primary key (the email) and no version column, so Spring Data cannot
 * tell a new user from an existing one and `save()` falls back to `merge`: it would silently
 * overwrite an account created a moment earlier by a concurrent request (e.g. replacing its
 * password hash). Use [insert] for every new account; keep `save()` for updates.
 */
interface UserInsertRepository {
    /**
     * Persists [user] and flushes immediately, so a duplicate email fails here (as a
     * `DataIntegrityViolationException`, translated by the repository proxy) and never overwrites.
     * Returns the same, now managed, instance (not a copy as `save()`'s merge would).
     */
    fun insert(user: User): User
}

class UserInsertRepositoryImpl(
    @PersistenceContext private val entityManager: EntityManager
) : UserInsertRepository {
    override fun insert(user: User): User {
        entityManager.persist(user)
        entityManager.flush()
        return user
    }
}
