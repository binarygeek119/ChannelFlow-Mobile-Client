package org.jellyfin.mobile.channelflow

import android.net.Uri
import kotlinx.serialization.Serializable

@Serializable
data class ChannelFlowConnection(
	val baseUrl: String,
	val m3uUrl: String,
	val epgUrl: String,
	val apiKey: String,
) {
	fun displayName(): String {
		val uri = runCatching { Uri.parse(baseUrl) }.getOrNull() ?: return baseUrl
		val host = uri.host?.removePrefix("www.").orEmpty()
		if (host.isBlank()) return baseUrl
		val port = uri.port
		return if (port != -1) "$host:$port" else host
	}

	fun withResolvedApiKey(): ChannelFlowConnection {
		val key = apiKey.ifBlank { ChannelFlowUrls.extractApiKey(m3uUrl) }
			.ifBlank { ChannelFlowUrls.extractApiKey(epgUrl) }
		return if (key == apiKey) this else copy(apiKey = key)
	}

	fun withApiKey(newKey: String): ChannelFlowConnection {
		val key = newKey.trim()
		if (key.isBlank() || key == apiKey) return this
		return copy(
			apiKey = key,
			m3uUrl = ChannelFlowUrls.withApiKey(m3uUrl, key),
			epgUrl = ChannelFlowUrls.withApiKey(epgUrl, key),
		)
	}
}

@Serializable
data class ChannelFlowSavedServer(
	val id: String,
	val connection: ChannelFlowConnection,
)
