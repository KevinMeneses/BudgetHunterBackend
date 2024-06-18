package com.meneses.controllers

import com.meneses.application.CollaborationManager
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.collaborationInitController(collaborationManager: CollaborationManager) {
    get("collaborate/start") {
        val code = collaborationManager.initialize()
        if (code == -1) {
            call.respond("Sorry, the collaboration has already started, try again later.")
        } else {
            call.respond(code)
        }
    }
}