package com.myth.model

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val userId: String,
    val username: String,
    val createdAt: Long
)

@Serializable
data class SessionUser(
    val userId: String,
    val username: String
)

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String
)

@Serializable
data class UsernameCheckResponse(
    val username: String,
    val available: Boolean
)
