package org.jellyfin.mobile.channelflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

internal object ChannelFlowConnectionPersistence {
	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	fun decode(text: String): ChannelFlowServersState? {
		val raw = text.trim()
		if (raw.isEmpty()) return null
		val stored = runCatching { json.decodeFromString<StoredState>(raw) }.getOrNull()
		if (stored != null && stored.hasServers()) return stored.normalized()
		val connection = runCatching { json.decodeFromString<ChannelFlowConnection>(raw) }.getOrNull()
		if (connection != null && connection.baseUrl.isNotBlank()) {
			return StoredState(
				connection = connection,
				favoriteChannelIds = stored?.favoriteChannelIds.orEmpty(),
			).normalized()
		}
		return null
	}

	fun encode(state: ChannelFlowServersState): String = json.encodeToString(
		StoredState(
			connection = state.connection,
			servers = state.servers,
			activeServerId = state.activeServerId,
			favoriteChannelIds = state.favoriteChannelIds,
		)
	)

	@Serializable
	internal data class StoredState(
		val connection: ChannelFlowConnection? = null,
		val servers: List<ChannelFlowSavedServer> = emptyList(),
		val activeServerId: String? = null,
		val favoriteChannelIds: Set<String> = emptySet(),
	) {
		fun hasServers(): Boolean = connection != null || servers.isNotEmpty()

		fun normalized(): ChannelFlowServersState {
			var nextServers = servers
			var nextActiveId = activeServerId
			if (nextServers.isEmpty() && connection != null) {
				nextActiveId = UUID.randomUUID().toString()
				nextServers = listOf(ChannelFlowSavedServer(id = nextActiveId, connection = connection))
			}
			val active = nextServers.firstOrNull { it.id == nextActiveId } ?: nextServers.firstOrNull()
			return ChannelFlowServersState(
				servers = nextServers.map { it.copy(connection = it.connection.withResolvedApiKey()) },
				activeServerId = active?.id,
				connection = active?.connection?.withResolvedApiKey(),
				favoriteChannelIds = favoriteChannelIds,
			)
		}
	}
}
