package com.myth.server.config

data class ServerConfig(
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080,
    val host: String = System.getenv("HOST") ?: "0.0.0.0",
    val dbUrl: String = System.getenv("DATABASE_URL") ?: "jdbc:sqlite:myth_metadata.db",
    val sessionTtlMillis: Long = 24 * 60 * 60 * 1000L, // 24 hours
    val volatileMessageTtlSeconds: Long = 60L, // 60 seconds TTL for undelivered messages
    val selfDestructCountdownSeconds: Int = 5 // 5 seconds self-destruct after read
)
