package com.budgethunter.categorization

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CategoryCacheTest {

    @Test
    fun `returns null for a description it has not seen`() {
        assertNull(CategoryCache().get("Rappi"))
    }

    @Test
    fun `descriptions that normalise alike share an entry`() {
        val cache = CategoryCache()

        cache.put("RAPPI 8841", "GROCERIES")

        assertEquals("GROCERIES", cache.get("rappi 9921"))
        assertEquals("GROCERIES", cache.get("Rappí"))
        assertEquals(1, cache.size)
    }

    @Test
    fun `ignores descriptions with nothing identifying`() {
        val cache = CategoryCache()

        cache.put("12345", "FOOD")

        assertEquals(0, cache.size)
        assertNull(cache.get("12345"))
        assertNull(cache.get(""))
    }

    @Test
    fun `rejects a category outside the closed list`() {
        assertThrows<IllegalArgumentException> { CategoryCache().put("rappi", "Groceries") }
    }

    @Test
    fun `rejects the uncategorized placeholder, which is not an answer`() {
        assertThrows<IllegalArgumentException> { CategoryCache().put("rappi", "UNCATEGORIZED") }
    }

    @Test
    fun `can remember OTHER because it is an answer`() {
        val cache = CategoryCache()

        cache.put("xyzzy store", "OTHER")

        assertEquals("OTHER", cache.get("xyzzy store"))
    }

    @Test
    fun `drops the least recently used entry past its bound`() {
        val cache = CategoryCache(maxEntries = 2)
        cache.put("alpha", "FOOD")
        cache.put("beta", "HEALTH")
        cache.get("alpha") // alpha is now more recent than beta

        cache.put("gamma", "TAXES")

        assertEquals(2, cache.size)
        assertEquals("FOOD", cache.get("alpha"))
        assertNull(cache.get("beta"))
        assertEquals("TAXES", cache.get("gamma"))
    }

    @Test
    fun `requires a positive bound`() {
        assertThrows<IllegalArgumentException> { CategoryCache(maxEntries = 0) }
    }

    @Test
    fun `survives concurrent use without exceeding its bound`() {
        val cache = CategoryCache(maxEntries = 50)
        val pool = Executors.newFixedThreadPool(8)

        repeat(8) { worker ->
            pool.submit {
                repeat(500) { i ->
                    cache.put("merchant${worker}x${'a' + (i % 20)}${'a' + (i / 20 % 20)}", "FOOD")
                    cache.get("merchant${worker}x${'a' + (i % 20)}${'a' + (i / 20 % 20)}")
                }
            }
        }
        pool.shutdown()

        assertEquals(true, pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(true, cache.size <= 50)
    }
}
