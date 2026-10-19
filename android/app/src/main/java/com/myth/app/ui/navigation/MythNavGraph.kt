package com.myth.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.myth.app.data.remote.MythApiClient
import com.myth.app.domain.CallManager
import com.myth.app.domain.CallState
import com.myth.app.domain.ChatManager
import com.myth.app.ui.screens.*

@Composable
fun MythNavGraph(
    navController: NavHostController,
    apiClient: MythApiClient,
    chatManager: ChatManager,
    callManager: CallManager
) {
    val callState by callManager.callState.collectAsState()

    LaunchedEffect(callState) {
        when (val state = callState) {
            is CallState.Incoming -> {
                navController.navigate(Screen.IncomingCall.createRoute(state.callerUsername)) {
                    launchSingleTop = true
                }
            }
            is CallState.Active -> {
                val currentRoute = navController.currentDestination?.route
                if (currentRoute != Screen.ActiveCall.route) {
                    navController.navigate(Screen.ActiveCall.createRoute(state.contactUsername)) {
                        launchSingleTop = true
                        popUpTo(Screen.IncomingCall.route) { inclusive = true }
                    }
                }
            }
            else -> {}
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route
    ) {
        composable(Screen.Splash.route) {
            SplashScreen(
                chatManager = chatManager,
                onNavigateToWelcome = {
                    navController.navigate(Screen.Welcome.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                },
                onNavigateToMain = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Welcome.route) {
            WelcomeScreen(
                onNavigateToRegister = { navController.navigate(Screen.Register.route) },
                onNavigateToLogin = { navController.navigate(Screen.Login.route) }
            )
        }

        composable(Screen.Register.route) {
            RegisterScreen(
                apiClient = apiClient,
                chatManager = chatManager,
                onNavigateToMain = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = { navController.navigate(Screen.Login.route) }
            )
        }

        composable(Screen.Login.route) {
            LoginScreen(
                apiClient = apiClient,
                chatManager = chatManager,
                onNavigateToMain = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate(Screen.Register.route) }
            )
        }

        composable(Screen.Main.route) {
            MainScreen(
                apiClient = apiClient,
                chatManager = chatManager,
                onNavigateToConversation = { username ->
                    navController.navigate(Screen.Conversation.createRoute(username))
                },
                onNavigateToAddContact = { navController.navigate(Screen.AddContact.route) },
                onNavigateToRequests = { navController.navigate(Screen.ContactRequests.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToContactDetails = { username ->
                    navController.navigate(Screen.ContactDetails.createRoute(username))
                }
            )
        }

        composable(Screen.AddContact.route) {
            AddContactScreen(
                apiClient = apiClient,
                chatManager = chatManager,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ContactRequests.route) {
            ContactRequestsScreen(
                apiClient = apiClient,
                chatManager = chatManager,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Conversation.route,
            arguments = listOf(navArgument("username") { type = NavType.StringType })
        ) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username").orEmpty()
            ConversationScreen(
                contactUsername = username,
                chatManager = chatManager,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetails = { contactUser ->
                    navController.navigate(Screen.ContactDetails.createRoute(contactUser))
                },
                onInitiateCall = {
                    callManager.startCall(username)
                    navController.navigate(Screen.ActiveCall.createRoute(username))
                }
            )
        }

        composable(
            route = Screen.ContactDetails.route,
            arguments = listOf(navArgument("username") { type = NavType.StringType })
        ) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username").orEmpty()
            ContactDetailsScreen(
                contactUsername = username,
                apiClient = apiClient,
                chatManager = chatManager,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                apiClient = apiClient,
                chatManager = chatManager,
                onNavigateBack = { navController.popBackStack() },
                onLogoutComplete = {
                    navController.navigate(Screen.Welcome.route) {
                        popUpTo(Screen.Main.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.IncomingCall.route,
            arguments = listOf(navArgument("username") { type = NavType.StringType })
        ) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username").orEmpty()
            IncomingCallScreen(
                callerUsername = username,
                callManager = callManager,
                onCallDismissed = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.ActiveCall.route,
            arguments = listOf(navArgument("username") { type = NavType.StringType })
        ) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username").orEmpty()
            ActiveCallScreen(
                contactUsername = username,
                callManager = callManager,
                onCallEnded = { navController.popBackStack() }
            )
        }
    }
}
