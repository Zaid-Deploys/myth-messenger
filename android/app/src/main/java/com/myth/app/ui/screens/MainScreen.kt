package com.myth.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myth.app.data.local.ChatMessage
import com.myth.app.data.remote.MythApiClient
import com.myth.app.domain.ChatManager
import com.myth.model.ContactDto
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    apiClient: MythApiClient,
    chatManager: ChatManager,
    onNavigateToConversation: (String) -> Unit,
    onNavigateToAddContact: () -> Unit,
    onNavigateToRequests: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToContactDetails: (String) -> Unit
) {
    var contacts by remember { mutableStateOf<List<ContactDto>>(emptyList()) }
    var pendingRequestCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }

    val conversations by chatManager.chatStore.conversations.collectAsState()
    val scope = rememberCoroutineScope()

    fun loadData() {
        val user = chatManager.currentUser ?: return
        isLoading = true
        scope.launch {
            apiClient.getContacts(user.token).onSuccess { resp ->
                contacts = resp.contacts
                pendingRequestCount = resp.incomingRequests.size
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadData()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Myth", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "@${chatManager.currentUser?.username.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToRequests) {
                        BadgedBox(
                            badge = {
                                if (pendingRequestCount > 0) {
                                    Badge { Text("$pendingRequestCount") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = "Contact Requests")
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToAddContact) {
                Icon(Icons.Default.Add, contentDescription = "Add Contact")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Security badge / non-persistence disclosure banner
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Zero persistence active: chats exist only in memory and disappear 5s after read.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isLoading && contacts.isEmpty() && conversations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (contacts.isEmpty() && conversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Forum,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No active conversations",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Add a contact using their exact username to initiate an end-to-end encrypted session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // Active conversations section
                    if (conversations.isNotEmpty()) {
                        item {
                            Text(
                                text = "Active Volatile Chats",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        items(conversations.keys.toList()) { username ->
                            val msgs = conversations[username].orEmpty()
                            val lastMsg = msgs.lastOrNull()
                            ActiveConversationItem(
                                username = username,
                                lastMessage = lastMsg,
                                messageCount = msgs.size,
                                onClick = { onNavigateToConversation(username) }
                            )
                        }
                    }

                    // Contacts list section
                    item {
                        Text(
                            text = "Accepted Contacts (${contacts.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(contacts) { contact ->
                        ContactItem(
                            contact = contact,
                            onChatClick = { onNavigateToConversation(contact.username) },
                            onDetailsClick = { onNavigateToContactDetails(contact.username) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveConversationItem(
    username: String,
    lastMessage: ChatMessage?,
    messageCount: Int,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text("@$username", style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                text = if (lastMessage?.selfDestructCountdown != null) {
                    "Self-destructing in ${lastMessage.selfDestructCountdown}s"
                } else {
                    "${messageCount} volatile message(s)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (lastMessage?.selfDestructCountdown != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                Icons.Default.ChatBubble,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        },
        modifier = Modifier.clickable { onClick() }
    )
    HorizontalDivider()
}

@Composable
fun ContactItem(
    contact: ContactDto,
    onChatClick: () -> Unit,
    onDetailsClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text("@${contact.username}", style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                "E2EE Verified Contact",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        },
        leadingContent = {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onChatClick) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDetailsClick) {
                    Icon(Icons.Default.Info, contentDescription = "Details")
                }
            }
        },
        modifier = Modifier.clickable { onChatClick() }
    )
    HorizontalDivider()
}
