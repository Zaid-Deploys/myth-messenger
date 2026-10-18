package com.myth.server.routes

import com.myth.model.ErrorResponse
import com.myth.model.RegisterPreKeysRequest
import com.myth.model.SessionUser
import com.myth.server.database.DatabaseFactory
import com.myth.server.security.TokenService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.preKeyRoutes(
    db: DatabaseFactory,
    tokenService: TokenService
) {
    fun authenticate(call: ApplicationCall): SessionUser? {
        val token = call.request.header("Authorization")?.removePrefix("Bearer ")?.trim() ?: ""
        return tokenService.validateToken(token)
    }

    route("/api/v1/keys") {

        post("/register") {
            val user = authenticate(call)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "Valid authentication token required"))
                return@post
            }

            val req = try {
                call.receive<RegisterPreKeysRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PAYLOAD", "Malformed prekey payload"))
                return@post
            }

            db.registerPreKeys(user.userId, req)
            call.respond(HttpStatusCode.OK, mapOf("status" to "keys_registered"))
        }

        get("/{username}") {
            val user = authenticate(call)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "Valid authentication token required"))
                return@get
            }

            val targetUsername = call.parameters["username"]?.lowercase()?.trim() ?: ""
            val bundle = db.getPreKeyBundle(targetUsername)

            if (bundle != null) {
                call.respond(HttpStatusCode.OK, bundle)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Prekey bundle not found for user"))
            }
        }
    }
}
