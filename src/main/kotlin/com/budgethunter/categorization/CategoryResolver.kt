package com.budgethunter.categorization

/**
 * Finds a category for descriptions by trying the cheapest source first: keyword rules, then what
 * was already learned (the cache), and only then the remote classifier. Its answers are written
 * back to the cache, so a merchant is asked about once.
 *
 * `null` means "no answer": the description is blank, nothing matched and the remote classifier
 * (if any) had no opinion or was unavailable. Callers leave the entry alone in that case.
 */
class CategoryResolver(
    private val rules: CategoryClassifier,
    private val cache: CategoryCache,
    private val remote: CategoryClassifier?
) {

    fun resolve(description: String): String? = resolve(listOf(description)).single()

    /** One answer per description, in order, asking the remote classifier once for all that remain. */
    fun resolve(descriptions: List<String>): List<String?> {
        val ruled = rules.classify(descriptions)
        val answers = descriptions.mapIndexed { index, description -> ruled[index] ?: cache.get(description) }

        val missing = descriptions.indices.filter { answers[it] == null && hasIdentifyingText(descriptions[it]) }
        if (remote == null || missing.isEmpty()) return answers

        val asked = remote.classify(missing.map { descriptions[it] })
        val merged = answers.toMutableList()
        missing.forEachIndexed { position, index ->
            asked[position]?.let { category ->
                cache.put(descriptions[index], category)
                merged[index] = category
            }
        }
        return merged
    }

    private fun hasIdentifyingText(description: String) = DescriptionNormalizer.normalize(description).isNotEmpty()
}
