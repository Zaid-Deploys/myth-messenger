package com.myth.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myth.app.data.remote.MythApiClient
import com.myth.app.domain.ChatManager
import com.myth.model.ContactDto
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    apiClient: MythApiClient,
    chatManager: ChatManager,
    onNavigateBack: () -> Unit
) {
    var searchUsername by remember { mutableStateOf("") }
    var searchResult by remember { mutableStateOf<ContactDto?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val user = chatManager.currentUser

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Contact") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = "Discover Contact",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Enter the exact username of the person you wish to contact.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchUsername,
                    onValueChange = {
                        searchUsername = it.lowercase().trim()
                        searchResult = null
                        statusMessage = null
                    },
                    label = { Text("Exact Username") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (searchUsername.isEmpty() || user == null) return@Button
                        isSearching = true
                        statusMessage = null
                        scope.launch {
                            apiClient.lookupContact(user.token, searchUsername)
                                .onSuccess { dto ->
                                    searchResult = dto
                                    isSearching = false
                                }
                                .onFailure { err ->
                                    isSearching = false
                                    isError = true
                                    statusMessage = err.message ?: "Account not found"
                                }
                        }
                    },
                    enabled = searchUsername.isNotEmpty() && !isSearching
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (searchResult != null) {
                val found = searchResult!!
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "@${found.username}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Status: ${found.status.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                if (user == null) return@Button
                                isSending = true
                                scope.launch {
                                    apiClient.sendContactRequest(user.token, found.username)
                                        .onSuccess {
                                            isSending = false
                                            isError = false
                                            statusMessage = "Contact request sent to @${found.username}"
                                            searchResult = null
                                        }
                                        .onFailure { err ->
                                            isSending = false
                                            isError = true
                                            statusMessage = err.message ?: "Could not send request"
                                        }
                                }
                            },
                            enabled = !isSending,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isSending) "Sending..." else "Send Contact Request")
                        }
                    }
                }
            }

            if (statusMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = statusMessage!!,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
