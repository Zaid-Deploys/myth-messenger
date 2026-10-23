package com.myth.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myth.app.data.remote.MythApiClient
import com.myth.app.domain.ChatManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    apiClient: MythApiClient,
    chatManager: ChatManager,
    onNavigateBack: () -> Unit,
    onLogoutComplete: () -> Unit
) {
    val user = chatManager.currentUser
    val scope = rememberCoroutineScope()
    var showLogoutDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                ),
                title = {
                    Text(
                        "Settings & Privacy",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Account Section
                Text(
                    text = "YOUR ACCOUNT",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .border(
                                    2.dp,
                                    Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF06B6D4))),
                                    CircleShape
                                )
                                .background(Color(0xFF0F172A)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = user?.username.orEmpty().take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(
                                text = "@${user?.username.orEmpty()}",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "UID: ${user?.userId.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = Color(0xFF94A3B8)
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Security & Architecture Section
                Text(
                    text = "ZERO-KNOWLEDGE ARCHITECTURE",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        DisclosureItem(
                            icon = Icons.Default.Lock,
                            iconColor = Color(0xFF6366F1),
                            title = "Double Ratchet E2EE",
                            description = "Every message gets fresh single-use symmetric keys with forward secrecy & post-compromise break-in recovery. Only conversation participants hold keys."
                        )

                        HorizontalDivider(color = Color(0xFF334155), thickness = 0.8.dp)

                        DisclosureItem(
                            icon = Icons.Default.VisibilityOff,
                            iconColor = Color(0xFF06B6D4),
                            title = "Zero Message Persistence",
                            description = "The server never stores ciphertext, plaintext, logs, attachments, or queued envelopes. All relay occurs strictly in volatile RAM."
                        )

                        HorizontalDivider(color = Color(0xFF334155), thickness = 0.8.dp)

                        DisclosureItem(
                            icon = Icons.Default.Timer,
                            iconColor = Color(0xFFF59E0B),
                            title = "Read-Based Self-Destruction",
                            description = "Messages commence a 5-second countdown timer only after both parties view them, and are then irreversibly zeroed from device RAM."
                        )

                        HorizontalDivider(color = Color(0xFF334155), thickness = 0.8.dp)

                        DisclosureItem(
                            icon = Icons.Default.Shield,
                            iconColor = Color(0xFF10B981),
                            title = "Hardware Keystore & Anti-Screen Protections",
                            description = "Cryptographic identity roots are shielded inside the Android Keystore hardware module. FLAG_SECURE protects against OS screenshots and recent apps previews."
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Honest Security Boundaries
                Text(
                    text = "HONEST SECURITY BOUNDARIES",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = Color(0xFFF59E0B),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161F30)),
                    border = BorderStroke(1.dp, Color(0x44F59E0B)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "No software application can make physically impossible guarantees. Myth cannot defend against:",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color(0xFFE2E8F0),
                                lineHeight = 18.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "• An external camera taking a photograph of the physical display.",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF94A3B8))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "• Compromised, rooted Android devices with kernel memory instrumentation.",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF94A3B8))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "• Operating-system level spyware or hardware keyloggers.",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF94A3B8))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Logout Button
                Button(
                    onClick = { showLogoutDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Log Out & Purge Memory",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }

                if (showLogoutDialog) {
                    AlertDialog(
                        containerColor = Color(0xFF1E293B),
                        titleContentColor = Color.White,
                        textContentColor = Color(0xFF94A3B8),
                        onDismissRequest = { showLogoutDialog = false },
                        title = { Text("Log Out & Purge Memory?") },
                        text = {
                            Text(
                                "Logging out will invalidate your session and wipe all volatile messages, active cryptographic sessions, and ratchet keys from device memory."
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showLogoutDialog = false
                                    scope.launch {
                                        if (user != null) {
                                            apiClient.logout(user.token)
                                        }
                                        chatManager.logout()
                                        onLogoutComplete()
                                    }
                                }
                            ) {
                                Text("Confirm Logout", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showLogoutDialog = false }) {
                                Text("Cancel", color = Color(0xFF94A3B8))
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DisclosureItem(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )
            )
        }
    }
}
