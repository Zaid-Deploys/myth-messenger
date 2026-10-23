package com.myth.server.database

import com.myth.model.*
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

data class UserRecord(
    val id: String,
    val username: String,
    val passwordHash: String,
    val createdAt: Long
)

class DatabaseFactory(private val rawDbUrl: String) {

    val isPostgres: Boolean
    private val parsedDbUrl: String
    private val parsedProps: java.util.Properties?

    init {
        val (url, props, postgres) = parseDatabaseUrl(rawDbUrl)
        parsedDbUrl = url
        parsedProps = props
        isPostgres = postgres

        if (isPostgres) {
            Class.forName("org.postgresql.Driver")
        } else {
            Class.forName("org.sqlite.JDBC")
        }
        initSchema()
    }

    private fun getConnection(): Connection {
        return if (parsedProps != null) {
            DriverManager.getConnection(parsedDbUrl, parsedProps)
        } else {
            DriverManager.getConnection(parsedDbUrl)
        }
    }

    private fun initSchema() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                if (!isPostgres) {
                    stmt.execute("PRAGMA foreign_keys = ON;")
                }

                // 1. Users metadata
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id TEXT PRIMARY KEY,
                        username TEXT UNIQUE NOT NULL,
                        password_hash TEXT NOT NULL,
                        created_at BIGINT NOT NULL
                    );
                """.trimIndent())

                // 2. Public cryptographic prekeys only (NO private keys)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS public_keys (
                        user_id TEXT PRIMARY KEY,
                        identity_key TEXT NOT NULL,
                        signed_prekey_id INTEGER NOT NULL,
                        signed_prekey TEXT NOT NULL,
                        signed_prekey_sig TEXT NOT NULL,
                        created_at BIGINT NOT NULL,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    );
                """.trimIndent())

                // 3. One-time prekeys pool
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS one_time_prekeys (
                        user_id TEXT NOT NULL,
                        key_id INTEGER NOT NULL,
                        public_key TEXT NOT NULL,
                        PRIMARY KEY(user_id, key_id),
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    );
                """.trimIndent())

                // 4. Contact relationships and pending requests
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS contacts (
                        id TEXT PRIMARY KEY,
                        requester_id TEXT NOT NULL,
                        addressee_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        UNIQUE(requester_id, addressee_id),
                        FOREIGN KEY(requester_id) REFERENCES users(id) ON DELETE CASCADE,
                        FOREIGN KEY(addressee_id) REFERENCES users(id) ON DELETE CASCADE
                    );
                """.trimIndent())

                // 5. Blocked relationships
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS blocked_users (
                        blocker_id TEXT NOT NULL,
                        blocked_id TEXT NOT NULL,
                        created_at BIGINT NOT NULL,
                        PRIMARY KEY(blocker_id, blocked_id),
                        FOREIGN KEY(blocker_id) REFERENCES users(id) ON DELETE CASCADE,
                        FOREIGN KEY(blocked_id) REFERENCES users(id) ON DELETE CASCADE
                    );
                """.trimIndent())

                // Explicit verification assertion: Ensure zero message tables exist
                val checkSql = if (isPostgres) {
                    "SELECT table_name as name FROM information_schema.tables WHERE table_schema = 'public' AND table_name LIKE '%message%';"
                } else {
                    "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '%message%';"
                }
                val rs = stmt.executeQuery(checkSql)
                if (rs.next()) {
                    throw IllegalStateException("CRITICAL INVARIANT VIOLATION: Message table detected in persistent database")
                }
            }
        }
    }

    companion object {
        fun parseDatabaseUrl(rawUrl: String): Triple<String, java.util.Properties?, Boolean> {
            if (rawUrl.startsWith("postgres://") || rawUrl.startsWith("postgresql://")) {
                val uri = java.net.URI(rawUrl)
                val userInfo = uri.userInfo?.split(":")
                val user = userInfo?.getOrNull(0)
                val pass = userInfo?.getOrNull(1)
                val host = uri.host
                val port = if (uri.port != -1) uri.port else 5432
                val dbName = uri.path.trimStart('/')
                val props = java.util.Properties()
                user?.let { props.setProperty("user", it) }
                pass?.let { props.setProperty("password", it) }
                props.setProperty("sslmode", "require")
                val jdbcUrl = "jdbc:postgresql://$host:$port/$dbName"
                return Triple(jdbcUrl, props, true)
            } else if (rawUrl.startsWith("jdbc:postgresql:")) {
                return Triple(rawUrl, null, true)
            }
            return Triple(rawUrl, null, false)
        }
    }

    // --- User Operations ---

    fun createUser(username: String, passwordHash: String): UserRecord {
        val normalized = username.lowercase().trim()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        getConnection().use { conn ->
            val ps = conn.prepareStatement("INSERT INTO users(id, username, password_hash, created_at) VALUES(?, ?, ?, ?);")
            ps.setString(1, id)
            ps.setString(2, normalized)
            ps.setString(3, passwordHash)
            ps.setLong(4, now)
            ps.executeUpdate()
        }
        return UserRecord(id, normalized, passwordHash, now)
    }

    fun getUserByUsername(username: String): UserRecord? {
        val normalized = username.lowercase().trim()
        getConnection().use { conn ->
            val ps = conn.prepareStatement("SELECT id, username, password_hash, created_at FROM users WHERE username = ?;")
            ps.setString(1, normalized)
            val rs = ps.executeQuery()
            if (rs.next()) {
                return UserRecord(
                    id = rs.getString("id"),
                    username = rs.getString("username"),
                    passwordHash = rs.getString("password_hash"),
                    createdAt = rs.getLong("created_at")
                )
            }
        }
        return null
    }

    fun getUserById(userId: String): UserRecord? {
        getConnection().use { conn ->
            val ps = conn.prepareStatement("SELECT id, username, password_hash, created_at FROM users WHERE id = ?;")
            ps.setString(1, userId)
            val rs = ps.executeQuery()
            if (rs.next()) {
                return UserRecord(
                    id = rs.getString("id"),
                    username = rs.getString("username"),
                    passwordHash = rs.getString("password_hash"),
                    createdAt = rs.getLong("created_at")
                )
            }
        }
        return null
    }

    fun isUsernameAvailable(username: String): Boolean {
        val normalized = username.lowercase().trim()
        getConnection().use { conn ->
            val ps = conn.prepareStatement("SELECT 1 FROM users WHERE username = ?;")
            ps.setString(1, normalized)
            val rs = ps.executeQuery()
            return !rs.next()
        }
    }

    // --- PreKey Operations ---

    fun registerPreKeys(userId: String, request: RegisterPreKeysRequest) {
        val now = System.currentTimeMillis()
        getConnection().use { conn ->
            conn.autoCommit = false
            try {
                // Upsert public_keys
                val ps = conn.prepareStatement("""
                    INSERT INTO public_keys(user_id, identity_key, signed_prekey_id, signed_prekey, signed_prekey_sig, created_at)
                    VALUES(?, ?, ?, ?, ?, ?)
                    ON CONFLICT(user_id) DO UPDATE SET
                        identity_key = excluded.identity_key,
                        signed_prekey_id = excluded.signed_prekey_id,
                        signed_prekey = excluded.signed_prekey,
                        signed_prekey_sig = excluded.signed_prekey_sig,
                        created_at = excluded.created_at;
                """.trimIndent())
                ps.setString(1, userId)
                ps.setString(2, request.identityKeyBase64)
                ps.setInt(3, request.signedPreKeyId)
                ps.setString(4, request.signedPreKeyBase64)
                ps.setString(5, request.signedPreKeySignatureBase64)
                ps.setLong(6, now)
                ps.executeUpdate()

                // Insert one time prekeys
                val opkPs = conn.prepareStatement("INSERT INTO one_time_prekeys(user_id, key_id, public_key) VALUES(?, ?, ?) ON CONFLICT(user_id, key_id) DO UPDATE SET public_key = EXCLUDED.public_key;")
                for (opk in request.oneTimePreKeys) {
                    opkPs.setString(1, userId)
                    opkPs.setInt(2, opk.keyId)
                    opkPs.setString(3, opk.publicKeyBase64)
                    opkPs.addBatch()
                }
                opkPs.executeBatch()
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    fun getPreKeyBundle(username: String): PreKeyBundleDto? {
        val user = getUserByUsername(username) ?: return null
        getConnection().use { conn ->
            // Fetch public keys
            val ps = conn.prepareStatement("SELECT identity_key, signed_prekey_id, signed_prekey, signed_prekey_sig FROM public_keys WHERE user_id = ?;")
            ps.setString(1, user.id)
            val rs = ps.executeQuery()
            if (!rs.next()) return null

            val identityKey = rs.getString("identity_key")
            val signedPreKeyId = rs.getInt("signed_prekey_id")
            val signedPreKey = rs.getString("signed_prekey")
            val signedPreKeySig = rs.getString("signed_prekey_sig")

            // Atomically fetch and consume one one-time prekey if available
            var oneTimePreKey: OneTimePreKeyDto? = null
            val opkPs = conn.prepareStatement("SELECT key_id, public_key FROM one_time_prekeys WHERE user_id = ? LIMIT 1;")
            opkPs.setString(1, user.id)
            val opkRs = opkPs.executeQuery()
            if (opkRs.next()) {
                val keyId = opkRs.getInt("key_id")
                val keyB64 = opkRs.getString("public_key")
                oneTimePreKey = OneTimePreKeyDto(keyId, keyB64)

                val delPs = conn.prepareStatement("DELETE FROM one_time_prekeys WHERE user_id = ? AND key_id = ?;")
                delPs.setString(1, user.id)
                delPs.setInt(2, keyId)
                delPs.executeUpdate()
            }

            return PreKeyBundleDto(
                userId = user.id,
                username = user.username,
                identityKeyBase64 = identityKey,
                signedPreKeyId = signedPreKeyId,
                signedPreKeyBase64 = signedPreKey,
                signedPreKeySignatureBase64 = signedPreKeySig,
                oneTimePreKey = oneTimePreKey
            )
        }
    }

    // --- Contact Operations ---

    fun createContactRequest(requesterId: String, addresseeUsername: String): ContactRequestDto {
        val addressee = getUserByUsername(addresseeUsername)
            ?: throw NoSuchElementException("User not found")

        if (requesterId == addressee.id) {
            throw IllegalArgumentException("Cannot send contact request to oneself")
        }

        if (isBlocked(addressee.id, requesterId)) {
            throw IllegalStateException("Contact request blocked")
        }

        val requester = getUserById(requesterId)!!
        val now = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()

        getConnection().use { conn ->
            val ps = conn.prepareStatement("""
                INSERT INTO contacts(id, requester_id, addressee_id, status, created_at, updated_at)
                VALUES(?, ?, ?, ?, ?, ?)
                ON CONFLICT(requester_id, addressee_id) DO UPDATE SET
                    status = CASE WHEN contacts.status = 'REJECTED' THEN 'PENDING' ELSE contacts.status END,
                    updated_at = excluded.updated_at;
            """.trimIndent())
            ps.setString(1, requestId)
            ps.setString(2, requesterId)
            ps.setString(3, addressee.id)
            ps.setString(4, ContactStatus.PENDING.name)
            ps.setLong(5, now)
            ps.setLong(6, now)
            ps.executeUpdate()
        }

        return ContactRequestDto(
            requestId = requestId,
            requesterUsername = requester.username,
            addresseeUsername = addressee.username,
            status = ContactStatus.PENDING,
            createdAt = now
        )
    }

    fun respondContactRequest(requestId: String, addresseeId: String, accept: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val newStatus = if (accept) ContactStatus.ACCEPTED else ContactStatus.REJECTED
        getConnection().use { conn ->
            val ps = conn.prepareStatement("""
                UPDATE contacts SET status = ?, updated_at = ?
                WHERE id = ? AND addressee_id = ? AND status = 'PENDING';
            """.trimIndent())
            ps.setString(1, newStatus.name)
            ps.setLong(2, now)
            ps.setString(3, requestId)
            ps.setString(4, addresseeId)
            return ps.executeUpdate() > 0
        }
    }

    fun areAcceptedContacts(usernameA: String, usernameB: String): Boolean {
        val userA = getUserByUsername(usernameA) ?: return false
        val userB = getUserByUsername(usernameB) ?: return false

        getConnection().use { conn ->
            val ps = conn.prepareStatement("""
                SELECT 1 FROM contacts
                WHERE ((requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?))
                AND status = 'ACCEPTED';
            """.trimIndent())
            ps.setString(1, userA.id)
            ps.setString(2, userB.id)
            ps.setString(3, userB.id)
            ps.setString(4, userA.id)
            val rs = ps.executeQuery()
            return rs.next()
        }
    }

    fun blockUser(blockerId: String, targetUsername: String): Boolean {
        val target = getUserByUsername(targetUsername) ?: return false
        val now = System.currentTimeMillis()
        getConnection().use { conn ->
            val ps = conn.prepareStatement("INSERT INTO blocked_users(blocker_id, blocked_id, created_at) VALUES(?, ?, ?) ON CONFLICT(blocker_id, blocked_id) DO UPDATE SET created_at = EXCLUDED.created_at;")
            ps.setString(1, blockerId)
            ps.setString(2, target.id)
            ps.setLong(3, now)
            ps.executeUpdate()

            // Update any contact relationship to BLOCKED
            val updatePs = conn.prepareStatement("""
                UPDATE contacts SET status = 'BLOCKED', updated_at = ?
                WHERE (requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?);
            """.trimIndent())
            updatePs.setLong(1, now)
            updatePs.setString(2, blockerId)
            updatePs.setString(3, target.id)
            updatePs.setString(4, target.id)
            updatePs.setString(5, blockerId)
            updatePs.executeUpdate()
        }
        return true
    }

    fun unblockUser(blockerId: String, targetUsername: String): Boolean {
        val target = getUserByUsername(targetUsername) ?: return false
        getConnection().use { conn ->
            val ps = conn.prepareStatement("DELETE FROM blocked_users WHERE blocker_id = ? AND blocked_id = ?;")
            ps.setString(1, blockerId)
            ps.setString(2, target.id)
            return ps.executeUpdate() > 0
        }
    }

    fun isBlocked(userAId: String, userBId: String): Boolean {
        getConnection().use { conn ->
            val ps = conn.prepareStatement("""
                SELECT 1 FROM blocked_users
                WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?);
            """.trimIndent())
            ps.setString(1, userAId)
            ps.setString(2, userBId)
            ps.setString(3, userBId)
            ps.setString(4, userAId)
            val rs = ps.executeQuery()
            return rs.next()
        }
    }

    fun getContactList(userId: String): ContactListResponse {
        val contacts = mutableListOf<ContactDto>()
        val incoming = mutableListOf<ContactRequestDto>()
        val outgoing = mutableListOf<ContactRequestDto>()
        val blocked = mutableListOf<String>()

        getConnection().use { conn ->
            // 1. Accepted contacts
            val ps = conn.prepareStatement("""
                SELECT c.id, c.status, c.created_at,
                       CASE WHEN c.requester_id = ? THEN u2.username ELSE u1.username END as other_username,
                       CASE WHEN c.requester_id = ? THEN pk2.identity_key ELSE pk1.identity_key END as identity_key
                FROM contacts c
                JOIN users u1 ON c.requester_id = u1.id
                JOIN users u2 ON c.addressee_id = u2.id
                LEFT JOIN public_keys pk1 ON u1.id = pk1.user_id
                LEFT JOIN public_keys pk2 ON u2.id = pk2.user_id
                WHERE (c.requester_id = ? OR c.addressee_id = ?) AND c.status = 'ACCEPTED';
            """.trimIndent())
            ps.setString(1, userId)
            ps.setString(2, userId)
            ps.setString(3, userId)
            ps.setString(4, userId)
            val rs = ps.executeQuery()
            while (rs.next()) {
                contacts.add(
                    ContactDto(
                        id = rs.getString("id"),
                        username = rs.getString("other_username"),
                        status = ContactStatus.ACCEPTED,
                        createdAt = rs.getLong("created_at"),
                        identityKeyBase64 = rs.getString("identity_key")
                    )
                )
            }

            // 2. Incoming requests
            val incPs = conn.prepareStatement("""
                SELECT c.id, u.username as requester_username, c.status, c.created_at
                FROM contacts c
                JOIN users u ON c.requester_id = u.id
                WHERE c.addressee_id = ? AND c.status = 'PENDING';
            """.trimIndent())
            incPs.setString(1, userId)
            val incRs = incPs.executeQuery()
            val me = getUserById(userId)!!
            while (incRs.next()) {
                incoming.add(
                    ContactRequestDto(
                        requestId = incRs.getString("id"),
                        requesterUsername = incRs.getString("requester_username"),
                        addresseeUsername = me.username,
                        status = ContactStatus.PENDING,
                        createdAt = incRs.getLong("created_at")
                    )
                )
            }

            // 3. Outgoing requests
            val outPs = conn.prepareStatement("""
                SELECT c.id, u.username as addressee_username, c.status, c.created_at
                FROM contacts c
                JOIN users u ON c.addressee_id = u.id
                WHERE c.requester_id = ? AND c.status = 'PENDING';
            """.trimIndent())
            outPs.setString(1, userId)
            val outRs = outPs.executeQuery()
            while (outRs.next()) {
                outgoing.add(
                    ContactRequestDto(
                        requestId = outRs.getString("id"),
                        requesterUsername = me.username,
                        addresseeUsername = outRs.getString("addressee_username"),
                        status = ContactStatus.PENDING,
                        createdAt = outRs.getLong("created_at")
                    )
                )
            }

            // 4. Blocked
            val blkPs = conn.prepareStatement("""
                SELECT u.username FROM blocked_users b
                JOIN users u ON b.blocked_id = u.id
                WHERE b.blocker_id = ?;
            """.trimIndent())
            blkPs.setString(1, userId)
            val blkRs = blkPs.executeQuery()
            while (blkRs.next()) {
                blocked.add(blkRs.getString("username"))
            }
        }

        return ContactListResponse(
            contacts = contacts,
            incomingRequests = incoming,
            outgoingRequests = outgoing,
            blockedUsers = blocked
        )
    }
}
