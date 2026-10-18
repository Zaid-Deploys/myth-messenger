package com.myth.app.ui.navigation

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Welcome : Screen("welcome")
    object Register : Screen("register")
    object Login : Screen("login")
    object Main : Screen("main")
    object AddContact : Screen("add_contact")
    object ContactRequests : Screen("contact_requests")
    object Settings : Screen("settings")

    object Conversation : Screen("conversation/{username}") {
        fun createRoute(username: String) = "conversation/$username"
    }

    object ContactDetails : Screen("contact_details/{username}") {
        fun createRoute(username: String) = "contact_details/$username"
    }

    object IncomingCall : Screen("incoming_call/{username}") {
        fun createRoute(username: String) = "incoming_call/$username"
    }

    object ActiveCall : Screen("active_call/{username}") {
        fun createRoute(username: String) = "active_call/$username"
    }
}
