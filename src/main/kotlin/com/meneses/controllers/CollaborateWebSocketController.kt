package com.meneses.controllers

import com.meneses.application.CollaborationManager
import com.meneses.domain.BudgetDetail
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun Routing.collaborationWebSocketController(collaborationManager: CollaborationManager) {
    route("/collaborate") {
        webSocket {
            if (!collaborationManager.canCollaborate()) {
                close(
                    reason = CloseReason(
                        code = CloseReason.Codes.CANNOT_ACCEPT,
                        message = "Cannot accept connection, max collaborators reached"
                    )
                )
                return@webSocket
            }

            val code = call.request.queryParameters["code"]?.toIntOrNull() ?: -1
            val isAdded = collaborationManager.addCollaborator(code, this)
            if (!isAdded) {
                close(
                    reason = CloseReason(
                        code = CloseReason.Codes.CANNOT_ACCEPT,
                        message = "Invalid code"
                    )
                )
                return@webSocket
            }

            try {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val message = frame.readText()
                        val budgetDetail = json.decodeFromString<BudgetDetail>(message)
                        collaborationManager.sendBudgetDetailUpdate(budgetDetail)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                close(
                    reason = CloseReason(
                        code = CloseReason.Codes.CANNOT_ACCEPT,
                        message = e.message.orEmpty()
                    )
                )
            }
        }
    }
}