package com.myth.server.routes

import com.myth.model.*
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

fun Route.contactRoutes(
    db: DatabaseFactory,
    tokenService: TokenService
) {
    fun authenticate(call: ApplicationCall): SessionUser? {
        val token = call.request.header("Authorization")?.removePrefix("Bearer ")?.trim() ?: ""
        return tokenService.validateToken(token)
    }

    route("/api/v1/contacts") {

        get {
            val user = authenticate(call)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "Valid authentication token required"))
                return@get
            }
            val list = db.getContactList(user.userId)
            call.respond(HttpStatusCode.OK, list)
        }

        get("/lookup") {
            val user = authenticate(call)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "Valid authentication token required"))
                return@get
            }

            val targetUsername = call.request.queryParameters["username"]?.lowercase()?.trim() ?: ""
            if (targetUsername.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_PARAM", "Username query parameter is required"))
                return@get
            }

            val targetUser = db.getUserByUsername(targetUsername)
            if (targetUser == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Account not found"))
                return@get
            }

            // Check if requester is blocked by target
            if (db.isBlocked(targetUser.id, user.userId)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Account not found"))
                return@get
            }

            val bundle = db.getPreKeyBundle(targetUsername)
            call.respond(
                HttpStatusCode.OK,
                ContactDto(
                    id = targetUser.id,
                    username = targetUser.username,
                    status = if (db.areAcceptedContacts(user.username, targetUsername)) ContactStatus.ACCEPTED else ContactStatus.PENDING,
                    createdAt = targetUser.createdAt,
                    identityKeyBase64 = bundle?.identityKeyBase64
                )
            )
        }

        post("/request") {
            val user = authenticate(call)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "Valid authentication token required"))
                return@post
            }

            val req = try {
                call.receive<CreateContactRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PAYLOAD", "Malformed request"))
                return@post
            }

            val targetUsername = req.targetUsername.lowercase().trim()
            try {
                val created = db.createContactRequest(user.userId, targetUsername)
                call.respond(HttpStatusCode.Created, created)
            } catch (e: NoSuchElementException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Target user does not exist"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_REQUEST", e.message ?: "Invalid request"))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("BLOCKED", e.message ?: "Action not permitted"))
            }
        }

        post("/respond") {
            val user = authenticate(call)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "Valid authentication token required"))
                return@post
            }

            val req = try {
                call.receive<RespondContactRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PAYLOAD", "Malformed response payload"))
                return@post
            }

            val success = db.respondContactRequest(req.requestId, user.userId, req.accept)
            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("status" to if (req.accept) "accepted" else "rejected"))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Pending contact request not found"))
            }
        }

        post("/block") {
            val user = authenticate(call)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "Valid authentication token required"))
                return@post
            }

            val req = call.receive<BlockContactRequest>()
            val success = db.blockUser(user.userId, req.targetUsername)
            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "blocked"))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Target user not found"))
            }
        }

        post("/unblock") {
            val user = authenticate(call)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "Valid authentication token required"))
                return@post
            }

            val req = call.receive<UnblockContactRequest>()
            val success = db.unblockUser(user.userId, req.targetUsername)
            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "unblocked"))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Target user not found"))
            }
        }
    }
}
