package com.budgethunter.dto

/** What a categorisation run did to a budget. */
data class CategorizeEntriesResponse(
    /** Entries that were given a category by this run. */
    val categorized: Int,

    /**
     * Entries still waiting for one afterwards: ones the classifier had no better answer than "other" for,
     * or that could not be asked yet because the provider's quota ran out. Calling again later retries them.
     */
    val pending: Long
)
