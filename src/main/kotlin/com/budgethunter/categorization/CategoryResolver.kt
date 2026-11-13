package com.budgethunter.categorization

import com.budgethunter.model.EntryCategory

/**
 * Finds a category for descriptions by trying the cheapest source first: keyword rules, then what
 * was already learned (the cache), and only then the remote classifier. Its answers are written
 * back to the cache, so a merchant is asked about once.
 *
 * `null` means "no answer": the description is blank, nothing matched and the remote classifier
 * (if any) had no opinion or was unavailable, or it could not be asked because the [RemoteBudget]
 * ran out. Callers leave the entry alone in that case.
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

    /**
     * The answers, one per description in order, and the positions that were never sent to the remote
     * classifier because the budget ran out ([heldBack]; their answer is `null`).
     */
    data class Resolution(val answers: List<String?>, val heldBack: Set<Int>)

    fun resolve(description: String): String? = resolve(listOf(description)).single()

    /** One answer per description, in order, asking the remote classifier once for all that remain. */
    fun resolve(descriptions: List<String>): List<String?> = resolve(descriptions, RemoteBudget.UNLIMITED).answers

    /**
     * Like [resolve], but sends the remote classifier only as many distinct descriptions as [budget] grants.
     *
     * The unit is a distinct normalised description (the remote classifier sends each text once, whatever the
     * number of entries carrying it), granted in the order they first appear. Rules and cache never draw from
     * the budget. Units granted for descriptions the remote classifier then did not answer are given back.
     */
    fun resolve(descriptions: List<String>, budget: RemoteBudget): Resolution {
        val ruled = rules.classify(descriptions).map { category -> category?.takeUnless { it == EntryCategory.OTHER } }
        val answers = descriptions.mapIndexed { index, description -> ruled[index] ?: cache.get(description) }
        metrics.resolved(CategorizationMetrics.Layer.RULES, ruled.count { it != null })
        metrics.resolved(CategorizationMetrics.Layer.CACHE, answers.count { it != null } - ruled.count { it != null })

        val missing = descriptions.indices.filter { answers[it] == null && keyOf(descriptions[it]).isNotEmpty() }
        if (remote == null || missing.isEmpty()) return Resolution(answers, emptySet())

        val keys = missing.map { keyOf(descriptions[it]) }.distinct()
        val granted = budget.take(keys.size).coerceIn(0, keys.size)
        val allowed = keys.take(granted).toSet()
        val (sent, heldBack) = missing.partition { keyOf(descriptions[it]) in allowed }
        val merged = if (sent.isEmpty()) answers else askRemote(remote, descriptions, sent, answers, budget, granted)
        return Resolution(merged, heldBack.toSet())
    }

    /** Asks about [sent], and gives back what the remote classifier did not answer, even if it throws. */
    @Suppress("LongParameterList") // Private step of resolve; bundling these into a class would only add noise.
    private fun askRemote(
        remote: CategoryClassifier,
        descriptions: List<String>,
        sent: List<Int>,
        answers: List<String?>,
        budget: RemoteBudget,
        granted: Int
    ): List<String?> {
        val merged = answers.toMutableList()
        try {
            val asked = remote.classify(sent.map { descriptions[it] })
            sent.forEachIndexed { position, index ->
                asked[position]?.let { category ->
                    cache.put(descriptions[index], category)
                    merged[index] = category
                }
            }
            metrics.resolved(CategorizationMetrics.Layer.REMOTE, asked.count { it != null })
        } finally {
            val answered = sent.filter { merged[it] != null }.map { keyOf(descriptions[it]) }.distinct().size
            budget.giveBack(granted - answered)
        }
        return merged
    }

    private fun keyOf(description: String) = DescriptionNormalizer.normalize(description)
}
