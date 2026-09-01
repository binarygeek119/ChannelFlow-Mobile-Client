package org.jellyfin.mobile.channelflow

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

sealed class ChannelFlowAccessEvent {
	data object PairingRequired : ChannelFlowAccessEvent()
	data object ReloadGuide : ChannelFlowAccessEvent()
}

class ChannelFlowAccessGuard(
	context: Context,
	private val store: ChannelFlowConnectionStore,
	private val catalog: ChannelFlowGuideRepository,
) {
	private val app = context.applicationContext
	private val _events = MutableSharedFlow<ChannelFlowAccessEvent>(extraBufferCapacity = 1)
	val events: SharedFlow<ChannelFlowAccessEvent> = _events.asSharedFlow()

	fun forgetUnauthorized(connection: ChannelFlowConnection): Boolean {
		val apiKey = connection.apiKey
		if (apiKey.isBlank()) return false
		val wasActive = store.connection?.apiKey == apiKey
		if (!store.removeByApiKey(apiKey)) return false
		Timber.w("Dropped ChannelFlow server %s because its API key was rejected", connection.displayName())
		catalog.clear()
		when {
			!store.isConnected -> {
				_events.tryEmit(ChannelFlowAccessEvent.PairingRequired)
				app.startChannelFlowPairingFromEmpty()
			}
			wasActive -> {
				_events.tryEmit(ChannelFlowAccessEvent.ReloadGuide)
				app.reloadChannelFlowMain()
			}
		}
		return true
	}
}
