package com.myth.server.security

import com.myth.crypto.CryptoUtils
import com.myth.model.SessionUser
import java.util.concurrent.ConcurrentHashMap

data class SessionEntry(
    val user: SessionUser,
    val expiresAt: Long
)

class TokenService(private val ttlMillis: Long = 24 * 60 * 60 * 1000L) {
    private val sessions = ConcurrentHashMap<String, SessionEntry>()

    fun createSession(user: SessionUser): String {
        val tokenBytes = CryptoUtils.randomBytes(32)
        val token = CryptoUtils.toBase64(tokenBytes)
        val expiresAt = System.currentTimeMillis() + ttlMillis
        sessions[token] = SessionEntry(user, expiresAt)
        return token
    }

    fun validateToken(token: String): SessionUser? {
        val entry = sessions[token] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            sessions.remove(token)
            return null
        }
        return entry.user
    }

    fun revokeToken(token: String) {
        sessions.remove(token)
    }

    fun revokeAllForUser(userId: String) {
        val iter = sessions.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value.user.userId == userId) {
                iter.remove()
            }
        }
    }
}
