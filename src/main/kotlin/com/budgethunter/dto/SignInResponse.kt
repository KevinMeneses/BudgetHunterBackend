package com.budgethunter.dto

data class SignInResponse(
    val authToken: String,
    val refreshToken: String,
    val email: String,
    val name: String,
    // True only on a Google sign-in that linked an account and removed the password it had (UserService.linkGoogle),
    // so the app can say so. Always present; older app builds ignore it.
    val passwordRemoved: Boolean = false,
    // Only with passwordRemoved: the budgets the account shares with someone, which whoever set the account up may
    // have shared with an account of their own. Nothing is removed; the app lists them so the user can remove the
    // ones they do not recognise. Empty otherwise. Older app builds ignore it.
    val sharedBudgets: List<SharedBudgetNotice> = emptyList()
)
