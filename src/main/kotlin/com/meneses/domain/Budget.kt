package com.meneses.domain

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Budget(
    val id: Int = -1,
    val amount: Double = 0.0,
    val name: String = "",
    @EncodeDefault
    val frequency: Frequency = Frequency.UNIQUE
) {
    @Serializable
    enum class Frequency {
        UNIQUE,
        DAILY,
        WEEKLY,
        MONTHLY,
        ANNUAL;
    }
}
