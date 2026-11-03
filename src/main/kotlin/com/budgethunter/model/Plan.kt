package com.budgethunter.model

/**
 * What an account has paid for. Everyone starts on [FREE]; [PREMIUM] is granted by the store
 * purchase (RevenueCat) and taken back when it lapses. Stored by name in `users.plan`.
 */
enum class Plan { FREE, PREMIUM }
