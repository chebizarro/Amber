package com.greenart7c3.nostrsigner

import android.app.Application
import android.content.Intent
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.greenart7c3.nostrsigner.database.AppDatabase
import com.greenart7c3.nostrsigner.models.AmberSettings
import com.greenart7c3.nostrsigner.service.ConnectivityService
import com.greenart7c3.nostrsigner.service.RelayDisconnectService
import com.greenart7c3.nostrsigner.ui.NotificationType
import com.vitorpamplona.ammolite.relays.Client
import com.vitorpamplona.ammolite.relays.Relay
import com.vitorpamplona.ammolite.relays.RelayPool
import com.vitorpamplona.ammolite.relays.RelaySetupInfo
import com.vitorpamplona.ammolite.relays.RelaySetupInfoToConnect
import com.vitorpamplona.ammolite.service.HttpClientManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class NostrSigner : Application() {
    val applicationIOScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var databases = ConcurrentHashMap<String, AppDatabase>()
    lateinit var settings: AmberSettings

    val isOnMobileDataState = mutableStateOf(false)
    val isOnWifiDataState = mutableStateOf(false)

    fun updateNetworkCapabilities(networkCapabilities: NetworkCapabilities): Boolean {
        val isOnMobileData = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isOnWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

        var changedNetwork = false

        if (isOnMobileDataState.value != isOnMobileData) {
            isOnMobileDataState.value = isOnMobileData

            changedNetwork = true
        }

        if (isOnWifiDataState.value != isOnWifi) {
            isOnWifiDataState.value = isOnWifi

            changedNetwork = true
        }

        if (changedNetwork) {
            if (isOnMobileData) {
                HttpClientManager.setDefaultTimeout(HttpClientManager.DEFAULT_TIMEOUT_ON_MOBILE)
            } else {
                HttpClientManager.setDefaultTimeout(HttpClientManager.DEFAULT_TIMEOUT_ON_WIFI)
            }
        }

        return changedNetwork
    }

    override fun onCreate() {
        super.onCreate()
        HttpClientManager.setDefaultUserAgent("Amber/${BuildConfig.VERSION_NAME}")
        instance = this

        LocalPreferences.allSavedAccounts(this).forEach {
            databases[it.npub] = AppDatabase.getDatabase(this, it.npub)
        }

        runBlocking {
            settings = LocalPreferences.loadSettingsFromEncryptedStorage()
        }

        try {
            this.startForegroundService(
                Intent(
                    this,
                    ConnectivityService::class.java,
                ),
            )
        } catch (e: Exception) {
            Log.e("NostrSigner", "Failed to start ConnectivityService", e)
        }
    }

    fun getDatabase(npub: String): AppDatabase {
        if (!databases.containsKey(npub)) {
            databases[npub] = AppDatabase.getDatabase(this, npub)
        }
        return databases[npub]!!
    }

    fun getSavedRelays(): Set<RelaySetupInfo> {
        val savedRelays = mutableSetOf<RelaySetupInfo>()
        LocalPreferences.allSavedAccounts(this).forEach { accountInfo ->
            val database = getDatabase(accountInfo.npub)
            database.applicationDao().getAllApplications().forEach {
                it.application.relays.forEach { setupInfo ->
                    savedRelays.add(setupInfo)
                }
            }
        }
        if (savedRelays.isEmpty()) {
            savedRelays.addAll(settings.defaultRelays)
        }
        return savedRelays
    }

    suspend fun checkForNewRelays(
        shouldReconnect: Boolean = false,
        newRelays: Set<RelaySetupInfo> = emptySet(),
    ) {
        val savedRelays = getSavedRelays() + newRelays

        val useProxy = LocalPreferences.allSavedAccounts(this).any {
            LocalPreferences.loadFromEncryptedStorage(this, it.npub)?.useProxy ?: false
        }

        if (settings.notificationType != NotificationType.DIRECT) {
            RelayPool.register(Client)
            savedRelays.forEach { setupInfo ->
                if (RelayPool.getRelay(setupInfo.url) == null) {
                    RelayPool.addRelay(
                        Relay(
                            setupInfo.url,
                            setupInfo.read,
                            setupInfo.write,
                            useProxy,
                            setupInfo.feedTypes,
                        ),
                    )
                }
            }
        }

        if (shouldReconnect) {
            checkIfRelaysAreConnected()
        }
        @Suppress("KotlinConstantConditions")
        if (settings.notificationType == NotificationType.DIRECT && BuildConfig.FLAVOR != "offline") {
            Client.reconnect(
                savedRelays.map { RelaySetupInfoToConnect(it.url, useProxy, it.read, it.write, it.feedTypes) }.toTypedArray(),
                true,
            )
        }
    }

    private suspend fun checkIfRelaysAreConnected(tryAgain: Boolean = true) {
        Log.d("NostrSigner", "Checking if relays are connected")
        RelayPool.getAll().forEach { relay ->
            if (!relay.isConnected()) {
                relay.connectAndRun {
                    val builder = OneTimeWorkRequest.Builder(RelayDisconnectService::class.java)
                    val inputData = Data.Builder()
                    inputData.putString("relay", relay.url)
                    builder.setInputData(inputData.build())
                    WorkManager.getInstance(getInstance()).enqueue(builder.build())
                }
            }
        }
        var count = 0
        while (RelayPool.getAll().any { !it.isConnected() } && count < 10) {
            count++
            delay(1000)
        }
        if (RelayPool.getAll().any { !it.isConnected() } && tryAgain) {
            checkIfRelaysAreConnected(false)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationIOScope.cancel()
    }

    companion object {
        @Volatile
        private var instance: NostrSigner? = null

        fun getInstance(): NostrSigner =
            instance ?: synchronized(this) {
                instance ?: NostrSigner().also { instance = it }
            }
    }
}
