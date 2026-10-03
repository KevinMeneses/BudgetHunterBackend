package com.budgethunter.dto

import jakarta.validation.constraints.Size

/**
 * The settings-screen preferences saved on the account.
 *
 * Nullable on the way out because null means "this account never saved one": a client uses that
 * to upload what the device already has instead of overwriting it with a default.
 */
data class UserPreferencesResponse(
    val smsReadingEnabled: Boolean?,
    val aiProcessingEnabled: Boolean?,
    /** The server-side id of the budget, not the device's local one. Null for "no default". */
    val defaultBudgetId: Long?,
    /** Null when never saved; an empty list when saved with no bank selected. */
    val selectedBankIds: List<String>?
)

/** Replaces every preference at once; the client always sends the full set it holds. */
data class UpdateUserPreferencesRequest(
    val smsReadingEnabled: Boolean,
    val aiProcessingEnabled: Boolean,
    val defaultBudgetId: Long? = null,
    @field:Size(max = 50, message = "Too many banks selected")
    val selectedBankIds: List<String> = emptyList()
)
