package com.myth.server

import com.myth.model.ErrorResponse
import com.myth.server.config.ServerConfig
import com.myth.server.database.DatabaseFactory
import com.myth.server.relay.VolatileRelayManager
import com.myth.server.routes.authRoutes
import com.myth.server.routes.contactRoutes
import com.myth.server.routes.healthRoutes
import com.myth.server.routes.preKeyRoutes
import com.myth.server.routes.webSocketRoutes
import com.myth.server.security.RateLimiter
import com.myth.server.security.TokenService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.serialization.json.Json
import java.time.Duration

val appJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

fun Application.module(
    config: ServerConfig = ServerConfig(),
    db: DatabaseFactory = DatabaseFactory(config.dbUrl),
    tokenService: TokenService = TokenService(config.sessionTtlMillis),
    rateLimiter: RateLimiter = RateLimiter()
) {
    install(ContentNegotiation) {
        json(appJson)
    }

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(30)
        maxFrameSize = 65536 // Bounded max payload size (64 KB)
        masking = false
    }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        anyHost()
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // Safe sanitized error response: NEVER leak stack trace, SQL queries, or crypto details
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("SERVER_ERROR", "An unexpected operational error occurred")
            )
        }
    }

    val relayManager = VolatileRelayManager(
        db = db,
        scope = this,
        json = appJson,
        ttlSeconds = config.volatileMessageTtlSeconds,
        selfDestructSeconds = config.selfDestructCountdownSeconds
    )

    routing {
        healthRoutes()
        authRoutes(db, tokenService, rateLimiter)
        contactRoutes(db, tokenService)
        preKeyRoutes(db, tokenService)
        webSocketRoutes(tokenService, relayManager, appJson)
    }
}

fun main() {
    val config = ServerConfig()
    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}
