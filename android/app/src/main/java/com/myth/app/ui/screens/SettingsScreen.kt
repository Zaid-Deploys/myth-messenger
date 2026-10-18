package com.myth.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
                title = { Text("Settings & Privacy") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Account section
            Text(
                text = "Account",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Logged in as @${user?.username.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "User ID: ${user?.userId.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Security & Architecture section
            Text(
                text = "Security Architecture & Privacy Model",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    DisclosureItem(
                        icon = Icons.Default.Lock,
                        title = "Double Ratchet E2EE",
                        description = "End-to-end encryption with forward secrecy and post-compromise break-in recovery. Only conversation participants possess decryption keys."
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    DisclosureItem(
                        icon = Icons.Default.VisibilityOff,
                        title = "Zero Message Persistence",
                        description = "The backend never saves message ciphertext, plaintext, attachments, or delivery queues. Messages are relayed in volatile RAM only."
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    DisclosureItem(
                        icon = Icons.Default.Timer,
                        title = "Read-Based Self-Destruction",
                        description = "Messages enter a 5-second countdown only after both sender and recipient confirm reading, and are then purged from RAM."
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    DisclosureItem(
                        icon = Icons.Default.Screenshot,
                        title = "Screenshot & Task Protections",
                        description = "FLAG_SECURE blocks OS-level screenshotting and recent-task previews. Android 14+ screenshot detection alerts participants."
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Honest Security Boundaries
            Text(
                text = "Honest Security Boundaries",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "No software application can make physically impossible guarantees. Myth cannot defend against:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• An external physical camera taking a photograph of the screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• Rooted devices with kernel-level memory instrumentation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• Operating-system level keyloggers or compromised firmware.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Logout button
            Button(
                onClick = { showLogoutDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Log Out & Purge Memory")
            }

            if (showLogoutDialog) {
                AlertDialog(
                    onDismissRequest = { showLogoutDialog = false },
                    title = { Text("Log Out?") },
                    text = {
                        Text("Logging out will revoke your active session and instantly purge all volatile messages, encryption sessions, and keys from device memory.")
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
                            Text("Confirm Logout", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLogoutDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun DisclosureItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
