package com.meneses.controllers

import com.meneses.application.CollaborationManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.collaborationInitController(collaborationManager: CollaborationManager) {
    get("collaborate/start") {
        val code = collaborationManager.initialize()
        println(code)
        if (code == -1) {
            call.respond(
                status = HttpStatusCode.Conflict,
                message = "Sorry, the collaboration has already started, try again later."
            )
        } else {
            call.respond(code)
        }
    }
}