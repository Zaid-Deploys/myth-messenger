package com.myth.app.data.remote

import android.util.Log
import com.myth.app.data.local.ServerConfigStore
import com.myth.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class MythApiClient(
    var baseUrl: String = ServerConfigStore.PRODUCTION_SERVER_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {
    fun updateBaseUrl(newBaseUrl: String) {
        baseUrl = newBaseUrl.trimEnd('/')
    }

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

        return executeEmptyRequest(request, "Logout failed")
    }

    suspend fun checkUsername(username: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/auth/check-username?username=$username")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val resp = json.decodeFromString<UsernameCheckResponse>(response.body?.string().orEmpty())
                    Result.success(resp.available)
                } else {
                    Result.failure(IOException("Failed to check username"))
                }
            }
        } catch (e: Exception) {
            Log.e("MythApiClient", "checkUsername failed", e)
            Result.failure(formatException(e))
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

        return executeEmptyRequest(request, "Failed to respond to contact request")
    }

    suspend fun blockUser(token: String, targetUsername: String): Result<Unit> {
        val body = json.encodeToString(BlockContactRequest(targetUsername)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/v1/contacts/block")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        return executeEmptyRequest(request, "Failed to block user")
    }

    suspend fun unblockUser(token: String, targetUsername: String): Result<Unit> {
        val body = json.encodeToString(UnblockContactRequest(targetUsername)).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/v1/contacts/unblock")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        return executeEmptyRequest(request, "Failed to unblock user")
    }

    suspend fun registerPreKeys(token: String, req: RegisterPreKeysRequest): Result<Unit> {
        val body = json.encodeToString(req).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/v1/keys/register")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        return executeEmptyRequest(request, "Failed to register prekeys")
    }

    suspend fun getPreKeyBundle(token: String, username: String): Result<PreKeyBundleDto> {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/keys/$username")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        return executeRequest(request)
    }

    suspend fun executeEmptyRequest(request: Request, defaultErrMsg: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val bodyStr = response.body?.string().orEmpty()
                    val err = try {
                        json.decodeFromString<ErrorResponse>(bodyStr).message
                    } catch (e: Exception) {
                        "$defaultErrMsg (HTTP ${response.code})"
                    }
                    Log.w("MythApiClient", "Request to ${request.url} failed: $err")
                    Result.failure(IOException(err))
                }
            }
        } catch (e: Exception) {
            Log.e("MythApiClient", "Request to ${request.url} threw exception", e)
            Result.failure(formatException(e))
        }
    }

    private suspend inline fun <reified T> executeRequest(request: Request): Result<T> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val data = json.decodeFromString<T>(bodyStr)
                    Result.success(data)
                } else {
                    val err = try {
                        json.decodeFromString<ErrorResponse>(bodyStr).message
                    } catch (e: Exception) {
                        "Server error (HTTP ${response.code})"
                    }
                    Log.w("MythApiClient", "Request to ${request.url} failed: $err")
                    Result.failure(IOException(err))
                }
            }
        } catch (e: Exception) {
            Log.e("MythApiClient", "Request to ${request.url} failed", e)
            Result.failure(formatException(e))
        }
    }

    fun formatException(e: Exception): IOException {
        val msg = when (e) {
            is java.net.UnknownHostException -> "Unable to reach server. Check internet connection."
            is java.net.ConnectException -> "Connection refused. Please try again."
            is java.net.SocketTimeoutException -> "Connection timed out. Server may be waking up, please retry."
            else -> e.localizedMessage ?: e.message ?: e.javaClass.simpleName
        }
        return IOException(msg, e)
    }
}
