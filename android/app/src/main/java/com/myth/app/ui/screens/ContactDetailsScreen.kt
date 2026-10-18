package com.myth.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myth.app.data.remote.MythApiClient
import com.myth.app.domain.ChatManager
import com.myth.crypto.CryptoUtils
import com.myth.crypto.SafetyNumber
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailsScreen(
    contactUsername: String,
    apiClient: MythApiClient,
    chatManager: ChatManager,
    onNavigateBack: () -> Unit
) {
    var isVerified by remember { mutableStateOf(false) }
    var safetyNumberText by remember { mutableStateOf<String>("Computing safety number...") }
    var isBlocked by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val user = chatManager.currentUser

    LaunchedEffect(contactUsername) {
        if (user != null) {
            scope.launch {
                apiClient.getPreKeyBundle(user.token, contactUsername).onSuccess { bundle ->
                    val remoteIdentityBytes = CryptoUtils.fromBase64(bundle.identityKeyBase64)
                    val localIdentityBytes = chatManager.identityEdKey.publicKeyBytes
                    val computed = SafetyNumber.computeSafetyNumber(localIdentityBytes, remoteIdentityBytes)
                    safetyNumberText = computed
                }.onFailure {
                    safetyNumberText = "Unavailable — could not retrieve contact identity key"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact Security") },
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "@$contactUsername",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isVerified) Icons.Default.Verified else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isVerified) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isVerified) "Identity Key Verified" else "Unverified Identity Key",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isVerified) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Safety Number Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Safety Number Fingerprint",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Compare this 60-digit number out-of-band with @$contactUsername to verify no interception or key substitution has occurred.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = safetyNumberText,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { isVerified = !isVerified },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isVerified) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.secondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (isVerified) Icons.Default.Close else Icons.Default.Check,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isVerified) "Mark Unverified" else "Mark as Verified")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Actions: Block / Unblock
            OutlinedButton(
                onClick = { showBlockDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Block, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isBlocked) "Unblock User" else "Block User")
            }

            if (showBlockDialog) {
                AlertDialog(
                    onDismissRequest = { showBlockDialog = false },
                    title = { Text(if (isBlocked) "Unblock @$contactUsername?" else "Block @$contactUsername?") },
                    text = {
                        Text(
                            if (isBlocked) {
                                "Unblocking this user will allow them to send contact requests."
                            } else {
                                "Blocked users cannot send you messages or contact requests. Active messages will be halted."
                            }
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showBlockDialog = false
                                if (user != null) {
                                    scope.launch {
                                        if (isBlocked) {
                                            apiClient.unblockUser(user.token, contactUsername)
                                            isBlocked = false
                                        } else {
                                            apiClient.blockUser(user.token, contactUsername)
                                            isBlocked = true
                                        }
                                    }
                                }
                            }
                        ) {
                            Text("Confirm")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBlockDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}
