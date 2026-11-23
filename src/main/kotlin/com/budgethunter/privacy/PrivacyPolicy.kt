package com.budgethunter.privacy

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The version of the privacy policy served at `/privacidad` (`static/privacidad.html`): the date of its
 * "Última actualización", as `YYYY-MM-DD`. The page shows it too, and `PrivacyPolicyPageTest` fails when the two
 * disagree, so change both together whenever the policy changes. Every account is then asked to accept again: the
 * app compares this with the version the account accepted (`GET /api/users/me`, `privacyPolicy`).
 */
const val PRIVACY_POLICY_VERSION = "2026-11-21"

/** [version] if it is the policy in force; an older or unknown one is ignored at sign-up and sign-in, not rejected. */
fun currentPolicyVersionOrNull(version: String?): String? = version?.takeIf { it == PRIVACY_POLICY_VERSION }

/** The acceptance date: the server's clock, to the microsecond PostgreSQL keeps, so the date answered is the one stored. */
fun privacyPolicyNow(clock: Clock): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
