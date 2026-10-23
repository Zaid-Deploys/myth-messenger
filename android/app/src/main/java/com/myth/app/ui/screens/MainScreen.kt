package com.myth.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .border(
                                    1.5.dp,
                                    Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF06B6D4))),
                                    CircleShape
                                )
                                .background(Color(0xFF1E293B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = chatManager.currentUser?.username.orEmpty().take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = "MYTH",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = Color.White
                                )
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981))
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "@${chatManager.currentUser?.username.orEmpty()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF06B6D4)
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToRequests) {
                        BadgedBox(
                            badge = {
                                if (pendingRequestCount > 0) {
                                    Badge(containerColor = Color(0xFFEF4444), contentColor = Color.White) {
                                        Text("$pendingRequestCount")
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.PersonAdd,
                                contentDescription = "Contact Requests",
                                tint = Color(0xFFF1F5F9)
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color(0xFFF1F5F9)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF090D16)
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToAddContact,
                containerColor = Color(0xFF6366F1),
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Contact", modifier = Modifier.size(28.dp))
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF090D16),
                            Color(0xFF0F172A),
                            Color(0xFF050811)
                        )
                    )
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Security status pill banner
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF131B2E).copy(alpha = 0.8f),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "RAM-Only Session: Zero message persistence across network & disk.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                if (isLoading && contacts.isEmpty() && conversations.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF06B6D4), strokeWidth = 2.5.dp)
                    }
                } else if (contacts.isEmpty() && conversations.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E293B)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Forum,
                                    contentDescription = null,
                                    tint = Color(0xFF64748B),
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No Active Chats",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tap the + button below to add a contact by username and start an encrypted session.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Active conversations section
                        if (conversations.isNotEmpty()) {
                            item {
                                Text(
                                    text = "ACTIVE SESSIONS",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        letterSpacing = 1.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF06B6D4)
                                    ),
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                                )
                            }

                            items(conversations.keys.toList()) { username ->
                                val msgs = conversations[username].orEmpty()
                                val lastMsg = msgs.lastOrNull()
                                ActiveConversationCard(
                                    username = username,
                                    lastMessage = lastMsg,
                                    messageCount = msgs.size,
                                    onClick = { onNavigateToConversation(username) }
                                )
                            }
                        }

                        // Contacts list section
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "ACCEPTED CONTACTS (${contacts.size})",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    letterSpacing = 1.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF64748B)
                                ),
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                            )
                        }

                        items(contacts) { contact ->
                            ContactCard(
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
}

@Composable
fun ActiveConversationCard(
    username: String,
    lastMessage: ChatMessage?,
    messageCount: Int,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF131B2E).copy(alpha = 0.85f),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(
                        1.5.dp,
                        Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF06B6D4))),
                        CircleShape
                    )
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = username.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "@$username",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (lastMessage?.selfDestructCountdown != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Purging in ${lastMessage.selfDestructCountdown}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFEF4444)
                        )
                    }
                } else {
                    Text(
                        text = "$messageCount volatile message(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF475569)
            )
        }
    }
}

@Composable
fun ContactCard(
    contact: ContactDto,
    onChatClick: () -> Unit,
    onDetailsClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF131B2E).copy(alpha = 0.65f),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChatClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B))
                    .border(1.dp, Color(0xFF334155), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.username.take(1).uppercase(),
                    color = Color(0xFF94A3B8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "@${contact.username}",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF1F5F9)
                    )
                )
                Text(
                    text = "E2EE Verified",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF10B981)
                )
            }

            Row {
                IconButton(onClick = onChatClick) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        contentDescription = "Chat",
                        tint = Color(0xFF06B6D4)
                    )
                }
                IconButton(onClick = onDetailsClick) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Details",
                        tint = Color(0xFF64748B)
                    )
                }
            }
        }
    }
}

