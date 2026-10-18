package com.myth.app

import android.app.Application
import com.myth.app.data.local.VolatileChatStore
import com.myth.app.data.remote.MythApiClient
import com.myth.app.data.remote.MythWebSocketClient
import com.myth.app.domain.ChatManager
import com.myth.app.security.AndroidKeystoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class MythApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var apiClient: MythApiClient
        private set
    lateinit var wsClient: MythWebSocketClient
        private set
    lateinit var chatStore: VolatileChatStore
        private set
    lateinit var chatManager: ChatManager
        private set
    lateinit var callManager: com.myth.app.domain.CallManager
        private set
    lateinit var keystoreManager: AndroidKeystoreManager
        private set

    override fun onCreate() {
        super.onCreate()

        // 1. Register Bouncy Castle provider
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())

        // 2. Initialize hardware-backed Keystore
        keystoreManager = AndroidKeystoreManager()

        // 3. Initialize networking & memory-only volatile store
        apiClient = MythApiClient()
        wsClient = MythWebSocketClient(scope = applicationScope)
        chatStore = VolatileChatStore(scope = applicationScope)

        // 4. Initialize Voice Call Manager
        callManager = com.myth.app.domain.CallManager(
            wsClient = wsClient,
            scope = applicationScope
        )

        // 5. Initialize Chat and Cryptography coordinator
        chatManager = ChatManager(
            apiClient = apiClient,
            wsClient = wsClient,
            chatStore = chatStore,
            callManager = callManager,
            scope = applicationScope
        )
    }
}
