package com.budgethunter.categorization

import com.budgethunter.model.EntryCategory

/**
 * Remembers which category a description got, keyed by its [DescriptionNormalizer] form, so a
 * merchant seen before never costs another call to the classifier.
 *
 * Bounded: past [maxEntries] the least recently used key is dropped. In memory only, so it starts
 * empty after a restart; that is acceptable because it only saves work, never data.
 */
class CategoryCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    // Access-ordered, so a read counts as a use and the eldest entry is the least recently used.
    private val entries = object : LinkedHashMap<String, String>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > maxEntries
    }

    /** The remembered category for [description], or `null` if it was never stored. */
    fun get(description: String): String? {
        val key = DescriptionNormalizer.normalize(description)
        if (key.isEmpty()) return null
        return synchronized(entries) { entries[key] }
    }

    /** Remembers [category] for [description]. Descriptions with nothing identifying are ignored. */
    fun put(description: String, category: String) {
        require(EntryCategory.isKnown(category)) { "Unknown category: $category" }
        val key = DescriptionNormalizer.normalize(description)
        if (key.isEmpty()) return
        synchronized(entries) { entries[key] = category }
    }

    val size: Int get() = synchronized(entries) { entries.size }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 10_000
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
