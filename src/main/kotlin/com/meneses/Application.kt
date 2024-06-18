package com.meneses

import com.meneses.application.CollaborationManager
import com.meneses.plugins.*
import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val collaborationManager = CollaborationManager()
    configureSockets()
    configureSerialization()
    configureMonitoring()
    configureRouting(collaborationManager)
}
