package com.budgethunter.categorization

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** How [CategoryResolver] shares a limited [RemoteBudget] out: only what reaches the remote classifier counts. */
class CategoryResolverBudgetTest {

    /** A budget with [left] units that records what was taken and given back. */
    private class FakeBudget(var left: Int) : RemoteBudget {
        val wanted = mutableListOf<Int>()
        val taken = mutableListOf<Int>()
        val givenBack = mutableListOf<Int>()

        override fun take(wanted: Int): Int = minOf(wanted, left).also {
            this.wanted += wanted
            left -= it
            taken += it
        }

        override fun giveBack(unused: Int) {
            left += unused
            givenBack += unused
        }

        val used get() = taken.sum() - givenBack.sum()
    }

    private val calls = mutableListOf<List<String>>()

    private val rules = CategoryClassifier { list -> list.map { if (it.contains("netflix")) "LEISURE" else null } }

    /** Knows every shop: answers OTHER for "mystery", nothing for "broken", GROCERIES for anything else. */
    private val remote = CategoryClassifier { list ->
        calls += list
        list.map {
            when {
                it.contains("broken") -> null
                it.contains("mystery") -> "OTHER"
                else -> "GROCERIES"
            }
        }
    }

    private fun resolver(cache: CategoryCache = CategoryCache(), remote: CategoryClassifier = this.remote) =
        CategoryResolver(rules, cache, remote)

    @Test
    fun `with 3 left and 10 new descriptions, 3 are sent and 7 held back`() {
        val budget = FakeBudget(3)
        val descriptions = (1..10).map { "shop ${'a' + it}" }

        val result = resolver().resolve(descriptions, budget)

        assertEquals(listOf(descriptions.take(3)), calls, "the first three, in order")
        assertEquals(List(3) { "GROCERIES" } + List<String?>(7) { null }, result.answers)
        assertEquals((3..9).toSet(), result.heldBack)
        assertEquals(3, budget.used)
        assertEquals(0, budget.left)
    }

    @Test
    fun `rules and cache answer without spending anything`() {
        val cache = CategoryCache().apply { put("Known Shop", "HEALTH") }
        val budget = FakeBudget(1)

        val result = resolver(cache).resolve(listOf("netflix", "known shop 12", "shop x", "shop y"), budget)

        assertEquals(listOf("LEISURE", "HEALTH", "GROCERIES", null), result.answers)
        assertEquals(setOf(3), result.heldBack)
        assertEquals(listOf(2), budget.wanted, "only the two the rules and cache did not know were asked for")
        assertEquals(1, budget.used)
    }

    @Test
    fun `nothing is taken when the rules and the cache answer everything`() {
        val budget = FakeBudget(0)

        val result = resolver().resolve(listOf("netflix", "netflix premium"), budget)

        assertEquals(listOf("LEISURE", "LEISURE"), result.answers)
        assertEquals(emptySet<Int>(), result.heldBack)
        assertEquals(emptyList<Int>(), budget.taken)
    }

    @Test
    fun `entries sharing a description are one unit`() {
        val budget = FakeBudget(1)

        val result = resolver().resolve(listOf("Rappi 8841", "rappi 9921", "RAPPÍ", "other shop"), budget)

        assertEquals(listOf(2), budget.wanted, "two distinct descriptions wanted")
        assertEquals(listOf("GROCERIES", "GROCERIES", "GROCERIES", null), result.answers)
        assertEquals(setOf(3), result.heldBack)
        assertEquals(1, budget.used)
    }

    @Test
    fun `with nothing left nothing is sent and every unknown description is held back`() {
        val budget = FakeBudget(0)

        val result = resolver().resolve(listOf("netflix", "shop a", "shop b", ""), budget)

        assertEquals(emptyList<List<String>>(), calls)
        assertEquals(listOf("LEISURE", null, null, null), result.answers)
        assertEquals(setOf(1, 2), result.heldBack, "a blank description is never sent, so it is not held back")
    }

    @Test
    fun `descriptions the remote classifier does not answer are given back, an OTHER is kept`() {
        val budget = FakeBudget(10)

        val result = resolver().resolve(listOf("broken one", "mystery", "shop", "broken two"), budget)

        assertEquals(listOf(null, "OTHER", "GROCERIES", null), result.answers)
        assertEquals(listOf(4), budget.taken)
        assertEquals(listOf(2), budget.givenBack)
        assertEquals(2, budget.used)
    }

    @Test
    fun `a remote classifier that answers nothing gives everything back`() {
        val budget = FakeBudget(5)

        resolver(remote = { list -> list.map { null } }).resolve(listOf("a shop", "b shop"), budget)

        assertEquals(0, budget.used)
        assertEquals(5, budget.left)
    }

    @Test
    fun `a remote classifier that throws gives everything back`() {
        val budget = FakeBudget(5)

        assertThrows<IllegalStateException> {
            resolver(remote = { error("provider bug") }).resolve(listOf("a shop", "b shop"), budget)
        }

        assertEquals(0, budget.used)
    }

    @Test
    fun `without a remote classifier nothing is taken`() {
        val budget = FakeBudget(5)

        val result = CategoryResolver(rules, CategoryCache(), null).resolve(listOf("a shop"), budget)

        assertEquals(listOf<String?>(null), result.answers)
        assertEquals(emptyList<Int>(), budget.taken)
    }
}
