package com.meneses.plugins

import com.meneses.application.CollaborationManager
import com.meneses.controllers.collaborationWebSocketController
import com.meneses.controllers.collaborationInitController
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting(collaborationManager: CollaborationManager) {
    routing {
        collaborationInitController(collaborationManager)
        collaborationWebSocketController(collaborationManager)
    }
}