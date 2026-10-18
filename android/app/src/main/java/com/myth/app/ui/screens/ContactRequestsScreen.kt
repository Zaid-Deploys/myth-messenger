package com.myth.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myth.app.data.remote.MythApiClient
import com.myth.app.domain.ChatManager
import com.myth.model.ContactRequestDto
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactRequestsScreen(
    apiClient: MythApiClient,
    chatManager: ChatManager,
    onNavigateBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var incomingRequests by remember { mutableStateOf<List<ContactRequestDto>>(emptyList()) }
    var outgoingRequests by remember { mutableStateOf<List<ContactRequestDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val user = chatManager.currentUser

    fun loadRequests() {
        if (user == null) return
        isLoading = true
        scope.launch {
            apiClient.getContacts(user.token).onSuccess { resp ->
                incomingRequests = resp.incomingRequests
                outgoingRequests = resp.outgoingRequests
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadRequests()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact Requests") },
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
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Incoming (${incomingRequests.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Outgoing (${outgoingRequests.size})") }
                )
            }

            if (isLoading && incomingRequests.isEmpty() && outgoingRequests.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (selectedTab == 0) {
                // Incoming Requests
                if (incomingRequests.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No pending incoming requests", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(incomingRequests) { req ->
                            IncomingRequestItem(
                                request = req,
                                onRespond = { accept ->
                                    if (user == null) return@IncomingRequestItem
                                    scope.launch {
                                        apiClient.respondContactRequest(user.token, req.requestId, accept)
                                        loadRequests()
                                    }
                                }
                            )
                        }
                    }
                }
            } else {
                // Outgoing Requests
                if (outgoingRequests.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No pending outgoing requests", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(outgoingRequests) { req ->
                            OutgoingRequestItem(request = req)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IncomingRequestItem(
    request: ContactRequestDto,
    onRespond: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text("@${request.requesterUsername}", style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text("Sent you a contact request", style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Row {
                IconButton(onClick = { onRespond(true) }) {
                    Icon(Icons.Default.Check, contentDescription = "Accept", tint = MaterialTheme.colorScheme.secondary)
                }
                IconButton(onClick = { onRespond(false) }) {
                    Icon(Icons.Default.Close, contentDescription = "Reject", tint = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    )
    HorizontalDivider()
}

@Composable
fun OutgoingRequestItem(request: ContactRequestDto) {
    ListItem(
        headlineContent = { Text("@${request.addresseeUsername}", style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                "Awaiting response...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
    HorizontalDivider()
}
