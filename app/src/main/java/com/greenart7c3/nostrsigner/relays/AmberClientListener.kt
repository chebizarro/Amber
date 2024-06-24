@file:OptIn(DelicateCoroutinesApi::class)

package com.greenart7c3.nostrsigner.relays

import com.greenart7c3.nostrsigner.LocalPreferences
import com.greenart7c3.nostrsigner.NostrSigner
import com.greenart7c3.nostrsigner.database.LogEntity
import com.greenart7c3.nostrsigner.ui.AccountStateViewModel
import com.vitorpamplona.ammolite.relays.Relay
import com.vitorpamplona.ammolite.relays.RelayPool
import com.vitorpamplona.quartz.events.Event
import com.vitorpamplona.quartz.events.EventInterface
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AmberListenerSingleton {
    var accountStateViewModel: AccountStateViewModel? = null
    private var listener: AmberClientListener? = null

    fun setListener(
        onDone: () -> Unit,
        onLoading: (Boolean) -> Unit,
        accountStateViewModel: AccountStateViewModel?,
    ) {
        listener = AmberClientListener(onDone, onLoading, accountStateViewModel)
    }

    fun getListener(): AmberClientListener? {
        return listener
    }
}

class AmberClientListener(
    val onDone: () -> Unit,
    val onLoading: (Boolean) -> Unit,
    val accountStateViewModel: AccountStateViewModel?,
) : RelayPool.Listener {
    override fun onAuth(relay: Relay, challenge: String) {
        LocalPreferences.currentAccount()?.let { account ->
            NostrSigner.instance.getDatabase(account).applicationDao().insertLog(
                LogEntity(
                    id = 0,
                    url = relay.url,
                    type = "onAuth",
                    message = "Authenticating",
                    time = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun onBeforeSend(relay: Relay, event: EventInterface) {
        LocalPreferences.currentAccount()?.let { account ->
            NostrSigner.instance.getDatabase(account).applicationDao().insertLog(
                LogEntity(
                    id = 0,
                    url = relay.url,
                    type = "onBeforeSend",
                    message = "Sending event ${event.id()}",
                    time = System.currentTimeMillis(),
                ),
            )
        }

        GlobalScope.launch(Dispatchers.Default) {
            delay(10000)
            onLoading(false)
            RelayPool.unregister(this@AmberClientListener)
        }
    }

    override fun onSend(relay: Relay, msg: String, success: Boolean) {
        LocalPreferences.currentAccount()?.let { account ->
            NostrSigner.instance.getDatabase(account).applicationDao().insertLog(
                LogEntity(
                    id = 0,
                    url = relay.url,
                    type = "onSend",
                    message = "message: $msg success: $success",
                    time = System.currentTimeMillis(),
                ),
            )
        }
        if (!success) {
            onLoading(false)
            accountStateViewModel?.toast("Error", "Failed to send event. Try again.")
            RelayPool.unregister(this@AmberClientListener)
        }
    }

    override fun onSendResponse(eventId: String, success: Boolean, message: String, relay: Relay) {
        LocalPreferences.currentAccount()?.let { account ->
            NostrSigner.instance.getDatabase(account).applicationDao().insertLog(
                LogEntity(
                    id = 0,
                    url = relay.url,
                    type = "onSendResponse",
                    message = "Success: $success Message: $message",
                    time = System.currentTimeMillis(),
                ),
            )
        }
        onLoading(false)
        if (success) {
            onDone()
            accountStateViewModel?.toast("Success", "Event sent successfully")
        } else {
            accountStateViewModel?.toast("Error", message)
            RelayPool.unregister(this@AmberClientListener)
        }
    }

    override fun onError(error: Error, subscriptionId: String, relay: Relay) {
        LocalPreferences.currentAccount()?.let { account ->
            NostrSigner.instance.getDatabase(account).applicationDao().insertLog(
                LogEntity(
                    id = 0,
                    url = relay.url,
                    type = "onError",
                    message = "${error.message}",
                    time = System.currentTimeMillis(),
                ),
            )
        }

        onLoading(false)
        accountStateViewModel?.toast("Error", error.message ?: "Unknown error")
        RelayPool.unregister(this@AmberClientListener)
    }

    override fun onEvent(event: Event, subscriptionId: String, relay: Relay, afterEOSE: Boolean) {
        LocalPreferences.currentAccount()?.let { account ->
            NostrSigner.instance.getDatabase(account).applicationDao().insertLog(
                LogEntity(
                    id = 0,
                    url = relay.url,
                    type = "onEvent",
                    message = "Received event ${event.id()} from subscription $subscriptionId afterEOSE: $afterEOSE",
                    time = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun onNotify(relay: Relay, description: String) {
        LocalPreferences.currentAccount()?.let { account ->
            NostrSigner.instance.getDatabase(account).applicationDao().insertLog(
                LogEntity(
                    id = 0,
                    url = relay.url,
                    type = "onNotify",
                    message = description,
                    time = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun onRelayStateChange(type: Relay.StateType, relay: Relay, channel: String?) {
        LocalPreferences.currentAccount()?.let { account ->
            NostrSigner.instance.getDatabase(account).applicationDao().insertLog(
                LogEntity(
                    id = 0,
                    url = relay.url,
                    type = "onRelayStateChange",
                    message = type.name,
                    time = System.currentTimeMillis(),
                ),
            )
        }
    }
}
