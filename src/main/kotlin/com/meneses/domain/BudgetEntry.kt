package com.meneses.domain

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.time.LocalDate

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BudgetEntry(
    val id: Int = -1,
    val budgetId: Int = -1,
    val amount: String = "",
    val description: String = "",
    @EncodeDefault
    val type: Type = Type.OUTCOME,
    val date: String = LocalDate.now().toString(),
    val invoice: String? = null,
    val isSelected: Boolean = false
) {
    @Serializable
    enum class Type {
        OUTCOME,
        INCOME
    }
}
