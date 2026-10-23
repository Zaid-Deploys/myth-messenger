package com.myth.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import com.myth.app.data.remote.ConnectionStatus
import com.myth.app.domain.ChatManager
import com.myth.model.DeliveryStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    contactUsername: String,
    chatManager: ChatManager,
    onNavigateBack: () -> Unit,
    onNavigateToDetails: (String) -> Unit,
    onInitiateCall: () -> Unit = {}
) {
    var inputText by remember { mutableStateOf("") }
    val conversations by chatManager.chatStore.conversations.collectAsState()
    val messages = conversations[contactUsername].orEmpty()

    var securityNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Automatically mark received incoming messages as read when displayed
    LaunchedEffect(messages.size) {
        messages.filter { !it.isOutgoing && it.selfDestructCountdown == null }.forEach { msg ->
            chatManager.markMessageAsRead(msg.id, contactUsername)
        }
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Listen for security alerts (e.g. screenshot detected by peer)
    LaunchedEffect(Unit) {
        chatManager.securityAlerts.collect { alert ->
            securityNotice = alert
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToDetails(contactUsername) }
                    ) {
                        // Contact Monogram Avatar
                        Box(
                            modifier = Modifier
                                .size(38.dp)
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
                                text = contactUsername.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "@$contactUsername",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.VerifiedUser,
                                    contentDescription = "Verified E2EE",
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Text(
                                text = "🔒 5s Self-Destruct • RAM Only",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    // Voice Call Button
                    IconButton(
                        onClick = onInitiateCall,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0x2210B981))
                    ) {
                        Icon(
                            Icons.Default.Call,
                            contentDescription = "Encrypted Call",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Identity & Fingerprint Button
                    IconButton(
                        onClick = { onNavigateToDetails(contactUsername) },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0x226366F1))
                    ) {
                        Icon(
                            Icons.Default.Fingerprint,
                            contentDescription = "Safety Fingerprint",
                            tint = Color(0xFF818CF8),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0F172A), Color(0xFF080D1A), Color(0xFF030712))
                    )
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Ephemeral chat notice pill banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0x1A06B6D4)),
                        border = BorderStroke(1.dp, Color(0x3306B6D4)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color(0xFF06B6D4),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Zero traces saved. Messages vanish 5s after both parties read.",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    color = Color(0xFFE2E8F0)
                                )
                            )
                        }
                    }
                }

                // Security Alert Banner (e.g. peer captured screenshot)
                AnimatedVisibility(
                    visible = securityNotice != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0x33EF4444)),
                        border = BorderStroke(1.dp, Color(0xFFEF4444)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = securityNotice.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color(0xFFFCA5A5),
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                            IconButton(
                                onClick = { securityNotice = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = Color(0xFFFCA5A5),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Chat Messages List
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (messages.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .border(
                                        1.5.dp,
                                        Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF06B6D4))),
                                        CircleShape
                                    )
                                    .background(Color(0xFF1E293B)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color(0xFF06B6D4),
                                    modifier = Modifier.size(34.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            Text(
                                text = "End-to-End Encrypted Session",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Direct cryptographic session established with @$contactUsername.\nMessages live exclusively in memory and self-destruct once read.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = Color(0xFF94A3B8),
                                    lineHeight = 18.sp
                                ),
                                modifier = Modifier.padding(horizontal = 20.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(messages, key = { it.id }) { msg ->
                                MessageBubble(msg = msg)
                            }
                        }
                    }
                }

                // Modern Bottom Message Input Bar
                Surface(
                    color = Color(0xFF0F172A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            BorderStroke(
                                1.dp,
                                Brush.verticalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
                            )
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = {
                                Text(
                                    "Encrypted message...",
                                    color = Color(0xFF64748B),
                                    fontSize = 14.sp
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF6366F1),
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF06B6D4),
                                focusedContainerColor = Color(0xFF1E293B),
                                unfocusedContainerColor = Color(0xFF1E293B)
                            ),
                            maxLines = 4,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(24.dp)
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        val hasContent = inputText.trim().isNotEmpty()

                        IconButton(
                            onClick = {
                                val textToSend = inputText.trim()
                                if (textToSend.isNotEmpty()) {
                                    inputText = ""
                                    scope.launch {
                                        chatManager.sendMessage(contactUsername, textToSend)
                                    }
                                }
                            },
                            enabled = hasContent,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(
                                    if (hasContent) {
                                        Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF06B6D4)))
                                    } else {
                                        Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF1E293B)))
                                    }
                                )
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (hasContent) Color.White else Color(0xFF64748B),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(msg: ChatMessage) {
    val alignment = if (msg.isOutgoing) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (msg.isOutgoing) 18.dp else 4.dp,
                        bottomEnd = if (msg.isOutgoing) 4.dp else 18.dp
                    )
                )
                .background(
                    if (msg.isOutgoing) {
                        Brush.linearGradient(
                            listOf(Color(0xFF4F46E5), Color(0xFF3730A3))
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(Color(0xFF1E293B), Color(0xFF162032))
                        )
                    }
                )
                .border(
                    BorderStroke(
                        1.dp,
                        if (msg.isOutgoing) Color(0x33818CF8) else Color(0xFF334155)
                    ),
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (msg.isOutgoing) 18.dp else 4.dp,
                        bottomEnd = if (msg.isOutgoing) 4.dp else 18.dp
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.White,
                        lineHeight = 20.sp,
                        fontSize = 15.sp
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Self-destruct timer countdown indicator
                    if (msg.selfDestructCountdown != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (msg.selfDestructCountdown!! <= 2) Color(0xFFDC2626) else Color(0xFFF59E0B)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = "Self-destruct countdown",
                                tint = Color.White,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${msg.selfDestructCountdown}s",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ),
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Delivery & Read indicators for outgoing messages
                    if (msg.isOutgoing) {
                        when {
                            msg.isReadByPeer -> {
                                Icon(
                                    Icons.Default.DoneAll,
                                    contentDescription = "Read by recipient",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            msg.deliveryStatus == DeliveryStatus.DELIVERED -> {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Delivered",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            msg.deliveryStatus == DeliveryStatus.EXPIRED -> {
                                Text(
                                    "Expired",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFEF4444)
                                )
                            }
                            msg.deliveryStatus == DeliveryStatus.UNDELIVERED -> {
                                Text(
                                    "Undelivered",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFEF4444)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
