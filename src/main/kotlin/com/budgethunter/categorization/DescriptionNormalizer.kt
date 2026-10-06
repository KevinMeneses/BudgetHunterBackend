package com.budgethunter.categorization

import java.text.Normalizer

/**
 * Reduces a description to the words that identify a merchant or purpose, so that
 * `"RAPPI 8841"`, `"rappi 9921"` and `"Rappí"` share one key.
 *
 * Lower-cases, strips accents, treats punctuation as a separator (apostrophes are dropped instead,
 * so `"McDonald's"` stays one word) and discards every token containing a digit, which is where
 * reference numbers, card suffixes and dates live. The result is words separated by single spaces,
 * or an empty string when nothing identifying is left.
 */
object DescriptionNormalizer {

    // Keeps a cache key (and the work done per description) bounded for pathological input.
    private const val MAX_LENGTH = 120

    private val combiningMarks = Regex("\\p{Mn}+")
    private val apostrophes = Regex("['’`]")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(description: String): String {
        val withoutAccents = combiningMarks.replace(
            Normalizer.normalize(description.lowercase(), Normalizer.Form.NFD),
            ""
        )
        return separators.split(apostrophes.replace(withoutAccents, ""))
            .filter { token -> token.isNotEmpty() && token.none(Char::isDigit) }
            .joinToString(" ")
            .take(MAX_LENGTH)
            .trim()
    }
}
