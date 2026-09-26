package com.budgethunter.model

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.time.Instant

@Entity
@Table(name = "users")
data class User(
    @field:Id
    @field:Email
    @field:NotBlank
    @field:Column(nullable = false, unique = true)
    val email: String,
    @field:NotBlank
    @field:Column(nullable = false)
    val name: String,
    // Nullable since a Google-only account has never set one. Every read has to guard against
    // that: see UserService.signIn, where a null here means "cannot authenticate by password".
    @field:Column
    var password: String? = null,
    // Google's "sub" claim. Unlike the email it never changes, so it is the primary key we match
    // a returning Google user on; the email is only a fallback used the first time we link.
    @field:Column(name = "google_subject", unique = true)
    var googleSubject: String? = null,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "auth_provider", nullable = false)
    var authProvider: AuthProvider = AuthProvider.PASSWORD,
    @field:Column(unique = true)
    var refreshToken: String? = null,
    @field:Column
    var refreshTokenExpiry: Instant? = null,
    @field:OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true)
    val userBudgets: MutableList<UserBudget> = mutableListOf(),
)
