package com.budgethunter.categorization

/**
 * Picks a category for entry descriptions. The seam between the rest of the app and whatever does
 * the guessing (keyword rules, Gemini, ...), so a provider can be swapped without touching callers.
 */
fun interface CategoryClassifier {

    /**
     * Classifies [descriptions] in one call, so a provider that charges or rate-limits per request
     * can batch them.
     *
     * Returns exactly one element per description, in the same order. An element is either a
     * member of [com.budgethunter.model.EntryCategory.ALL] or `null` for "no opinion" (blank text,
     * nothing recognised). `null` is not [com.budgethunter.model.EntryCategory.OTHER]: it lets the
     * caller try the next layer, whereas `OTHER` is an answer.
     *
     * Implementations must not throw for a bad description; they answer `null` for it.
     */
    fun classify(descriptions: List<String>): List<String?>
}
