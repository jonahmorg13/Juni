package com.juni.app

import android.app.Application
import android.net.Uri
import android.util.Log
import com.juni.app.data.db.AppDatabase
import com.juni.app.data.db.ConversationRepository
import com.juni.app.data.prefs.AppSettings
import com.juni.app.data.prefs.SecurePrefs
import com.juni.app.data.vault.VaultIndex
import com.juni.app.data.vault.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class JuniApp : Application() {

    lateinit var securePrefs: SecurePrefs
        private set

    lateinit var appSettings: AppSettings
        private set

    lateinit var conversationRepository: ConversationRepository
        private set

    lateinit var vaultIndex: VaultIndex
        private set

    /**
     * Images the user has attached in the chat composer but not yet sent.
     * Camera writes here on capture; Chat reads here to render thumbnails
     * and bakes the bytes into the user message on send. Cleared after send.
     */
    val composerImages: MutableStateFlow<List<ByteArray>> = MutableStateFlow(emptyList())

    // App-scoped scope for background work that should live past any one screen
    // (e.g. the vault index sync). SupervisorJob so a single failure doesn't
    // tear down the rest.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        securePrefs = SecurePrefs(this)
        appSettings = AppSettings(this)
        val db = AppDatabase.build(this)
        conversationRepository = ConversationRepository(db.conversations(), db.messages())
        vaultIndex = VaultIndex(db.vaultIndex())
        observeVaultForIndexing()
    }

    /**
     * Whenever a vault URI is set (or changes), kick off an FTS sync in the
     * background. First-time sync on a large vault can take a few seconds; we
     * intentionally don't block UI on it — searches return whatever's indexed
     * so far and fill in as files land.
     */
    private fun observeVaultForIndexing() {
        appScope.launch {
            appSettings.flow
                .map { it.vaultUri }
                .distinctUntilChanged()
                .collect { uri ->
                    if (uri.isNullOrBlank()) return@collect
                    try {
                        val vault = VaultRepository(this@JuniApp, Uri.parse(uri))
                        vaultIndex.ensureSynced(vault, uri)
                    } catch (t: Throwable) {
                        Log.w("juni-index", "Vault index sync failed for $uri", t)
                    }
                }
        }
    }

    fun addComposerImage(bytes: ByteArray) {
        composerImages.value = composerImages.value + bytes
    }

    fun clearComposerImages() {
        composerImages.value = emptyList()
    }

    companion object {
        private lateinit var instance: JuniApp

        fun get(): JuniApp = instance
    }
}
