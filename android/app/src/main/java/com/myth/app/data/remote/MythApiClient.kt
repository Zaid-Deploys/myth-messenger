package com.myth.app.data.remote

import com.myth.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class MythApiClient(
    private val baseUrl: String = "http://10.0.2.2:8080",
    private val client: OkHttpClient = OkHttpClient()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun register(username: String, password: String): Result<AuthResponse> {
        val body = json.encodeToString(RegisterRequest(username, password)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/v1/auth/register")
            .post(body)
            .build()

        return executeRequest(request)
    }

    suspend fun login(username: String, password: String): Result<AuthResponse> {
        val body = json.encodeToString(LoginRequest(username, password)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/v1/auth/login")
            .post(body)
            .build()

        return executeRequest(request)
    }

    suspend fun logout(token: String): Result<Unit> {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/auth/logout")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody(jsonMediaType))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IOException("Logout failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkUsername(username: String): Result<Boolean> {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/auth/check-username?username=$username")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val resp = json.decodeFromString<UsernameCheckResponse>(response.body?.string().orEmpty())
                    Result.success(resp.available)
                } else {
                    Result.failure(IOException("Failed to check username"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getContacts(token: String): Result<ContactListResponse> {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/contacts")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        return executeRequest(request)
    }

    suspend fun lookupContact(token: String, username: String): Result<ContactDto> {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/contacts/lookup?username=$username")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        return executeRequest(request)
    }

    suspend fun sendContactRequest(token: String, targetUsername: String): Result<ContactRequestDto> {
        val body = json.encodeToString(CreateContactRequest(targetUsername)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/v1/contacts/request")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        return executeRequest(request)
    }

    suspend fun respondContactRequest(token: String, requestId: String, accept: Boolean): Result<Unit> {
        val body = json.encodeToString(RespondContactRequest(requestId, accept)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/v1/contacts/respond")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IOException("Failed to respond to contact request: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun blockUser(token: String, targetUsername: String): Result<Unit> {
        val body = json.encodeToString(BlockContactRequest(targetUsername)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/v1/contacts/block")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IOException("Failed to block user: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unblockUser(token: String, targetUsername: String): Result<Unit> {
        val body = json.encodeToString(UnblockContactRequest(targetUsername)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/v1/contacts/unblock")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IOException("Failed to unblock user: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerPreKeys(token: String, req: RegisterPreKeysRequest): Result<Unit> {
        val body = json.encodeToString(req).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/v1/keys/register")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IOException("Failed to register prekeys: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPreKeyBundle(token: String, username: String): Result<PreKeyBundleDto> {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/keys/$username")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        return executeRequest(request)
    }

    private inline fun <reified T> executeRequest(request: Request): Result<T> {
        return try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val data = json.decodeFromString<T>(bodyStr)
                    Result.success(data)
                } else {
                    val err = try {
                        json.decodeFromString<ErrorResponse>(bodyStr).message
                    } catch (e: Exception) {
                        "HTTP error ${response.code}"
                    }
                    Result.failure(IOException(err))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
