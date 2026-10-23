package com.myth.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myth.app.domain.ChatManager
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    chatManager: ChatManager,
    onNavigateToWelcome: () -> Unit,
    onNavigateToMain: () -> Unit
) {
    LaunchedEffect(Unit) {
        val hasSession = chatManager.restorePersistedSession()
        delay(900)
        if (hasSession || chatManager.currentUser != null) {
            onNavigateToMain()
        } else {
            onNavigateToWelcome()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF090D16),
                        Color(0xFF0F172A),
                        Color(0xFF050811)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B))
                    .border(
                        2.dp,
                        Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF06B6D4))),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Myth Secure Icon",
                    tint = Color(0xFF06B6D4),
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "MYTH",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = Color.White
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Private • Ephemeral • End-to-End Encrypted",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF94A3B8)
            )
            Spacer(modifier = Modifier.height(36.dp))
            CircularProgressIndicator(
                color = Color(0xFF06B6D4),
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp
            )
        }
    }
}
