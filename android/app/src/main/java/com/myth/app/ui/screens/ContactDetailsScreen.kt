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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                ),
                title = {
                    Text(
                        "Contact Security",
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
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large Monogram Avatar
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(
                            2.dp,
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
                        fontSize = 32.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "@$contactUsername",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Verification Status Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isVerified) Color(0x2210B981) else Color(0x22F59E0B)
                        )
                        .border(
                            1.dp,
                            if (isVerified) Color(0xFF10B981) else Color(0xFFF59E0B),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = if (isVerified) Icons.Default.Verified else Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (isVerified) Color(0xFF10B981) else Color(0xFFF59E0B),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isVerified) "Identity Verified" else "Unverified Identity Key",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = if (isVerified) Color(0xFFA7F3D0) else Color(0xFFFDE68A)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Safety Number Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = Color(0xFF06B6D4),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Safety Number Fingerprint",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Compare this 60-digit fingerprint out-of-band with @$contactUsername to cryptographically verify end-to-end identity and confirm no MITM interception.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color(0xFF94A3B8),
                                lineHeight = 18.sp
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0F172A))
                                .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = safetyNumberText,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.sp,
                                    color = Color(0xFF38BDF8)
                                ),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = { isVerified = !isVerified },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isVerified) Color(0xFF334155) else Color(0xFF10B981)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(
                                imageVector = if (isVerified) Icons.Default.Close else Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isVerified) "Mark as Unverified" else "Mark as Verified",
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Actions: Block / Unblock
                OutlinedButton(
                    onClick = { showBlockDialog = true },
                    border = BorderStroke(1.dp, Color(0xFFEF4444)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0x11EF4444),
                        contentColor = Color(0xFFEF4444)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        Icons.Default.Block,
                        contentDescription = null,
                        tint = Color(0xFFEF4444)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBlocked) "Unblock User" else "Block User",
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFEF4444)
                    )
                }

                if (showBlockDialog) {
                    AlertDialog(
                        containerColor = Color(0xFF1E293B),
                        titleContentColor = Color.White,
                        textContentColor = Color(0xFF94A3B8),
                        onDismissRequest = { showBlockDialog = false },
                        title = { Text(if (isBlocked) "Unblock @$contactUsername?" else "Block @$contactUsername?") },
                        text = {
                            Text(
                                if (isBlocked) {
                                    "Unblocking this user will allow them to send contact requests and call you."
                                } else {
                                    "Blocked users cannot send you messages or contact requests. All active channels will be severed."
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
                                Text("Confirm", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBlockDialog = false }) {
                                Text("Cancel", color = Color(0xFF94A3B8))
                            }
                        }
                    )
                }
            }
        }
    }
}
