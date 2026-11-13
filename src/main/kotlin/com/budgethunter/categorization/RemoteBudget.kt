package com.budgethunter.categorization

/**
 * How many distinct descriptions [CategoryResolver] may send to the remote classifier in one run. The rules and
 * the cache never draw from it.
 */
interface RemoteBudget {

    /** Takes up to [wanted] descriptions' worth; returns how many it got, between 0 and [wanted]. */
    fun take(wanted: Int): Int

    /** Gives back [unused] of what [take] returned: descriptions the remote classifier was not able to answer. */
    fun giveBack(unused: Int)

    companion object {
        /** No limit: for callers that do not count (tests, single lookups). */
        val UNLIMITED: RemoteBudget = object : RemoteBudget {
            override fun take(wanted: Int) = wanted
            override fun giveBack(unused: Int) = Unit
        }
    }
}
