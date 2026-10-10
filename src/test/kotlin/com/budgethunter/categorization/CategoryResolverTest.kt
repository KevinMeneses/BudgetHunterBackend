package com.budgethunter.categorization

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CategoryResolverTest {

    private val calls = mutableListOf<List<String>>()
    private val rules = CategoryClassifier { list -> list.map { if (it.contains("netflix")) "LEISURE" else null } }

    /** A remote classifier that knows two merchants and records what it was asked. */
    private fun remote(answers: Map<String, String> = mapOf("rappi" to "GROCERIES", "dr smith" to "HEALTH")) =
        CategoryClassifier { list ->
            calls += list
            list.map { answers[DescriptionNormalizer.normalize(it)] }
        }

    private fun resolver(remote: CategoryClassifier? = remote(), cache: CategoryCache = CategoryCache()) =
        CategoryResolver(rules, cache, remote)

    @Test
    fun `rules answer first and the remote classifier is not asked`() {
        assertEquals("LEISURE", resolver().resolve("netflix"))
        assertEquals(emptyList<List<String>>(), calls)
    }

    @Test
    fun `asks the remote classifier for what the rules do not know and remembers the answer`() {
        val cache = CategoryCache()
        val resolver = resolver(cache = cache)

        assertEquals("GROCERIES", resolver.resolve("Rappi 8841"))
        assertEquals("GROCERIES", resolver.resolve("rappi 9921"))

        assertEquals(1, calls.size, "the second one is a cache hit")
        assertEquals("GROCERIES", cache.get("rappi"))
    }

    @Test
    fun `asks once for everything that remains, in a single call`() {
        val result = resolver().resolve(listOf("netflix", "Rappi", "", "Dr Smith", "unknown place"))

        assertEquals(listOf("LEISURE", "GROCERIES", null, "HEALTH", null), result)
        assertEquals(listOf(listOf("Rappi", "Dr Smith", "unknown place")), calls)
    }

    @Test
    fun `does not ask about descriptions with nothing identifying`() {
        assertEquals(listOf<String?>(null, null), resolver().resolve(listOf("", "12345")))
        assertEquals(emptyList<List<String>>(), calls)
    }

    @Test
    fun `works without a remote classifier`() {
        val resolver = resolver(remote = null)

        assertEquals("LEISURE", resolver.resolve("netflix"))
        assertEquals(null, resolver.resolve("Rappi"))
    }

    @Test
    fun `no answer from the remote classifier is not cached`() {
        val cache = CategoryCache()

        resolver(remote = remote(emptyMap()), cache = cache).resolve("mystery shop")

        assertEquals(0, cache.size)
    }
}
