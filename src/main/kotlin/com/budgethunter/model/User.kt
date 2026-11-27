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
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant
import java.util.UUID

// Each save() writes only the columns it changed. Without this Hibernate writes every column as it was read, so a
// request that read the account before Google was linked (a preference saved at that moment) would put back the
// password and the provider the link had just removed. Columns two requests both change still need the row lock
// (UserRepository.lockForUpdate).
@DynamicUpdate
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

    // Raised to end every session issued before: access tokens carry the version they were issued with (claim
    // "ver") and the JWT filter rejects any other. See V9__add_token_version.sql and UserService.signInWithGoogle.
    @field:Column(name = "token_version", nullable = false)
    var tokenVersion: Int = 0,

    // A Google sign-in removed the password and the app has not yet confirmed telling the user: every Google sign-in
    // answers passwordRemoved until then. See V10__add_link_notice_pending.sql and GoogleLinkNoticeService.
    @field:Column(name = "link_notice_pending", nullable = false)
    var linkNoticePending: Boolean = false,

    // App preferences. Null means the account never saved one, which is how a client tells a
    // fresh account (upload the device's values) from a chosen setting (adopt it).
    @field:Column(name = "sms_reading_enabled")
    var smsReadingEnabled: Boolean? = null,

    @field:Column(name = "ai_processing_enabled")
    var aiProcessingEnabled: Boolean? = null,

    // A plain id, not a relation: see V3__add_user_preferences.sql.
    @field:Column(name = "default_budget_id")
    var defaultBudgetId: Long? = null,

    // Comma-separated bank ids. Null is "never saved"; an empty string is "saved: none selected".
    @field:Column(name = "selected_bank_ids", length = 1000)
    var selectedBankIds: String? = null,

    // What the account paid for; decides how much paid AI it gets (see ReceiptQuotaService).
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "plan", nullable = false, length = 20)
    var plan: Plan = Plan.FREE,

    // Until when PREMIUM holds, as the store reported it (renewals and grace periods included). Null with
    // PREMIUM means it does not run out (a lifetime purchase, or a plan granted by hand). See [planAt].
    @field:Column(name = "plan_expires_at")
    var planExpiresAt: Instant? = null,

    // The id this account has at RevenueCat (its "app user id"). Random and never shown to anyone, so the
    // store and RevenueCat never see the email. Set once when the account is created; never changes.
    @field:Column(name = "billing_id", nullable = false, unique = true, updatable = false, length = 36)
    val billingId: String = UUID.randomUUID().toString(),

    // The privacy policy version the account accepted (com.budgethunter.privacy.PRIVACY_POLICY_VERSION at the time)
    // and when the server recorded it; null = never accepted. Proof of the user's authorization (Ley 1581 de 2012).
    // Written when the account is created, and afterwards only by PrivacyPolicyAcceptanceRepository.recordAcceptance:
    // updatable = false keeps a save() of an entity read earlier (a sign-in, a preference) from writing back nulls.
    @field:Column(name = "privacy_policy_version", length = 20, updatable = false)
    val privacyPolicyVersion: String? = null,

    @field:Column(name = "privacy_policy_accepted_at", updatable = false)
    val privacyPolicyAcceptedAt: Instant? = null,

    @field:OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true)
    val userBudgets: MutableList<UserBudget> = mutableListOf()
) {
    /** The plan in force at [now]: PREMIUM only until [planExpiresAt], in case the expiry webhook never came. */
    fun planAt(now: Instant): Plan = Plan.inForce(plan, planExpiresAt, now)
}
