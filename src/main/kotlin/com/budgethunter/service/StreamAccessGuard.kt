package com.budgethunter.service

import com.budgethunter.model.UserBudgetId
import com.budgethunter.repository.UserBudgetRepository
import com.budgethunter.repository.UserRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration

/**
 * Ends an event stream whose access has gone. The JWT filter checks the token and BudgetController.streamEntries
 * the membership only when the stream opens, and a stream can stay open for hours.
 */
@Component
class StreamAccessGuard(
    private val userRepository: UserRepository,
    private val userBudgetRepository: UserBudgetRepository,
    private val reactiveSseService: ReactiveSseService
) {

    /**
     * Emits once the stream of [budgetId] opened by [email] with a token of [tokenVersion] must end: at once when
     * the account's sessions are ended ([ReactiveSseService.sessionsEnded]), or at the first check, every
     * [checkEvery], that finds another token version or no membership (left or removed from the budget). A failed
     * check counts as access gone: the client reconnects and is checked from scratch.
     */
    fun accessEnded(budgetId: Long, email: String, tokenVersion: Int, checkEvery: Duration): Flux<Any> {
        val checksFail = Flux.interval(checkEvery, checkEvery)
            .concatMap {
                // Blocking JPA, so off the event loop.
                Mono.fromCallable { canKeepStreaming(budgetId, email, tokenVersion) }
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorReturn(false)
            }
            .filter { allowed -> !allowed }
            .next()
        return Flux.merge(reactiveSseService.sessionsEnded(email), checksFail)
    }

    @Transactional(readOnly = true)
    fun canKeepStreaming(budgetId: Long, email: String, tokenVersion: Int): Boolean =
        userRepository.findTokenVersion(email) == tokenVersion &&
            userBudgetRepository.existsById(UserBudgetId(budgetId, email))
}
