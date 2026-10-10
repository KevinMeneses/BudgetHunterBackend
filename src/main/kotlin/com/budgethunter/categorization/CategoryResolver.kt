package com.budgethunter.categorization

import com.budgethunter.model.EntryCategory

/**
 * Finds a category for descriptions by trying the cheapest source first: keyword rules, then what
 * was already learned (the cache), and only then the remote classifier. Its answers are written
 * back to the cache, so a merchant is asked about once.
 *
 * `null` means "no answer": the description is blank, nothing matched and the remote classifier
 * (if any) had no opinion or was unavailable. Callers leave the entry alone in that case.
 *
 * `OTHER` is a judgement ("I looked at it and nothing fits"), which only the remote classifier can
 * make, and the cache only repeats what it said. The rules cannot: finding no match says nothing
 * about the description. So an `OTHER` coming from the rules is discarded here, whatever the rules
 * implementation does, and the description goes on to the next layer instead of being settled.
 */
class CategoryResolver(
    private val rules: CategoryClassifier,
    private val cache: CategoryCache,
    private val remote: CategoryClassifier?,
    private val metrics: CategorizationMetrics = CategorizationMetrics()
) {

    fun resolve(description: String): String? = resolve(listOf(description)).single()

    /** One answer per description, in order, asking the remote classifier once for all that remain. */
    fun resolve(descriptions: List<String>): List<String?> {
        val ruled = rules.classify(descriptions).map { category -> category?.takeUnless { it == EntryCategory.OTHER } }
        val answers = descriptions.mapIndexed { index, description -> ruled[index] ?: cache.get(description) }
        metrics.resolved(CategorizationMetrics.Layer.RULES, ruled.count { it != null })
        metrics.resolved(CategorizationMetrics.Layer.CACHE, answers.count { it != null } - ruled.count { it != null })

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
        metrics.resolved(CategorizationMetrics.Layer.REMOTE, asked.count { it != null })
        return merged
    }

    private fun hasIdentifyingText(description: String) = DescriptionNormalizer.normalize(description).isNotEmpty()
}
