package com.myth.server.routes

import com.myth.crypto.Argon2idPasswordHasher
import com.myth.model.AuthResponse
import com.myth.model.ErrorResponse
import com.myth.model.LoginRequest
import com.myth.model.RegisterRequest
import com.myth.model.SessionUser
import com.myth.model.UsernameCheckResponse
import com.myth.server.database.DatabaseFactory
import com.myth.server.security.RateLimiter
import com.myth.server.security.TokenService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.authRoutes(
    db: DatabaseFactory,
    tokenService: TokenService,
    rateLimiter: RateLimiter
) {
    route("/api/v1/auth") {

        post("/register") {
            val ip = call.request.origin.remoteHost
            if (!rateLimiter.tryAcquire("register:$ip")) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("RATE_LIMITED", "Too many registration attempts. Please wait."))
                return@post
            }

            val req = try {
                call.receive<RegisterRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PAYLOAD", "Malformed registration payload"))
                return@post
            }

            val username = req.username.lowercase().trim()
            if (!username.matches(Regex("^[a-z0-9_]{3,32}$"))) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("INVALID_USERNAME", "Username must be 3-32 characters of lowercase letters, numbers, and underscores")
                )
                return@post
            }

            if (req.password.length < 8) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("WEAK_PASSWORD", "Password must be at least 8 characters long")
                )
                return@post
            }

            if (!db.isUsernameAvailable(username)) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("USERNAME_TAKEN", "Username is already taken")
                )
                return@post
            }

            val hash = Argon2idPasswordHasher.hashPassword(req.password.toCharArray())
            val user = db.createUser(username, hash)
            val sessionUser = SessionUser(user.id, user.username)
            val token = tokenService.createSession(sessionUser)

            call.respond(
                HttpStatusCode.Created,
                AuthResponse(token = token, userId = user.id, username = user.username, createdAt = user.createdAt)
            )
        }

        post("/login") {
            val ip = call.request.origin.remoteHost
            if (!rateLimiter.tryAcquire("login:$ip")) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("RATE_LIMITED", "Too many login attempts. Please wait."))
                return@post
            }

            val req = try {
                call.receive<LoginRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PAYLOAD", "Malformed login payload"))
                return@post
            }

            val username = req.username.lowercase().trim()
            val user = db.getUserByUsername(username)

            if (user == null || !Argon2idPasswordHasher.verifyPassword(req.password.toCharArray(), user.passwordHash)) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("INVALID_CREDENTIALS", "Incorrect username or password")
                )
                return@post
            }

            val sessionUser = SessionUser(user.id, user.username)
            val token = tokenService.createSession(sessionUser)

            call.respond(
                HttpStatusCode.OK,
                AuthResponse(token = token, userId = user.id, username = user.username, createdAt = user.createdAt)
            )
        }

        post("/logout") {
            val authHeader = call.request.header("Authorization") ?: ""
            val token = authHeader.removePrefix("Bearer ").trim()
            if (token.isNotEmpty()) {
                tokenService.revokeToken(token)
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "logged_out"))
        }

        get("/check-username") {
            val username = call.request.queryParameters["username"]?.lowercase()?.trim() ?: ""
            if (username.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_PARAM", "Username parameter required"))
                return@get
            }
            val available = db.isUsernameAvailable(username)
            call.respond(HttpStatusCode.OK, UsernameCheckResponse(username, available))
        }
    }
}
