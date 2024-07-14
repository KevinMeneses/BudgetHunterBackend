package com.meneses.application

import com.meneses.domain.BudgetDetail
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class CollaborationManager {
    private val collaborationCode = AtomicReference(NOT_INITIALIZED)
    private val collaborators = ConcurrentHashMap<Int, WebSocketSession>()

    fun canCollaborate() = collaborators.size <= MAX_COLLABORATORS

    fun initialize(): Int {
        val code = Random.nextInt(1000, 9999)
        val wasNotStarted = collaborationCode.compareAndSet(NOT_INITIALIZED, code)
        return if (wasNotStarted) code else -1
    }

    fun addCollaborator(code: Int, session: WebSocketSession): Boolean {
        if (collaborators.contains(session)) {
            return true
        }

        if (code == collaborationCode.get()) {
            collaborators[collaborators.size] = session
            return true
        }

        return false
    }

    suspend fun sendBudgetDetailUpdate(budgetDetail: BudgetDetail) {
        for (collaborator in collaborators.values) {
            collaborator.send(Json.encodeToString(budgetDetail))
        }
    }

    private companion object {
        const val MAX_COLLABORATORS = 5
        const val NOT_INITIALIZED = -1
    }
}