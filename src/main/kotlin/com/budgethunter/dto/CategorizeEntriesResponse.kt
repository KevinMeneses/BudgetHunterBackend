package com.budgethunter.dto

import io.swagger.v3.oas.annotations.media.Schema

/** What a categorisation run did to a budget. */
@Schema(description = "What a categorization run did to the budget")
data class CategorizeEntriesResponse(
    /** Entries that were given a category by this run. */
    @field:Schema(description = "Entries given a category by this run", example = "8")
    val categorized: Int,

    /**
     * Entries still waiting for one afterwards: ones nobody had an answer for, ones the AI could not be asked
     * about yet (provider quota, outage), and ones held back because the caller's monthly AI allowance ran
     * out. Calling again later retries them.
     */
    @field:Schema(description = "Entries still UNCATEGORIZED afterwards; calling again later retries them", example = "7")
    val pending: Long,

    /**
     * True when some entries were left waiting because the caller's monthly allowance of AI categorizations
     * ran out this run, and it is still used up afterwards (the app can offer Premium). The keyword rules and the
     * cache still applied. False when the entries wait because the AI failed (its units were given back).
     * Defaults keep older clients, which do not know the field, working.
     */
    @field:Schema(
        description = "True when some entries were left pending because the caller's monthly AI " +
            "categorizations ran out (rules and cache still applied)",
        example = "false"
    )
    val quotaExceeded: Boolean = false,

    /** The caller's AI categorization usage after this run. */
    @field:Schema(description = "The caller's AI categorization usage this month, after this run")
    val usage: CategorizationUsageResponse? = null
)
