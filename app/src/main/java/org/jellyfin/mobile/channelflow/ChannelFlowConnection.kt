package org.jellyfin.mobile.channelflow

import android.net.Uri
import kotlinx.serialization.Serializable

@Serializable
data class ChannelFlowEndpoint(
	val baseUrl: String,
	val m3uUrl: String,
	val epgUrl: String,
) {
	fun withApiKey(key: String): ChannelFlowEndpoint = copy(
		m3uUrl = ChannelFlowUrls.withApiKey(m3uUrl, key),
		epgUrl = ChannelFlowUrls.withApiKey(epgUrl, key),
	)
}

@Serializable
data class ChannelFlowConnection(
	val baseUrl: String,
	val m3uUrl: String,
	val epgUrl: String,
	val apiKey: String,
	val local: ChannelFlowEndpoint? = null,
	val public: ChannelFlowEndpoint? = null,
) {
	fun primaryEndpoint(): ChannelFlowEndpoint =
		ChannelFlowEndpoint(baseUrl = baseUrl, m3uUrl = m3uUrl, epgUrl = epgUrl)

	fun hasVariants(): Boolean = local != null || public != null

	fun endpointFor(kind: ChannelFlowNetworkKind): ChannelFlowEndpoint = when (kind) {
		ChannelFlowNetworkKind.LOCAL -> local ?: public ?: primaryEndpoint()
		ChannelFlowNetworkKind.PUBLIC -> public ?: local ?: primaryEndpoint()
		ChannelFlowNetworkKind.PRIMARY -> primaryEndpoint()
	}

	fun knownBaseUrls(): List<String> =
		listOfNotNull(baseUrl, local?.baseUrl, public?.baseUrl)
			.map { it.trim().trimEnd('/') }
			.filter { it.isNotBlank() }
			.distinctBy { it.lowercase() }

	fun sharesServer(other: ChannelFlowConnection): Boolean {
		val mine = knownBaseUrls()
		if (mine.isEmpty()) return false
		val theirs = other.knownBaseUrls()
		return mine.any { a -> theirs.any { b -> a.equals(b, ignoreCase = true) } }
	}

	fun displayName(): String {
		val preferred = public?.baseUrl?.takeIf { it.isNotBlank() } ?: baseUrl
		return hostLabel(preferred)
	}

	fun withResolvedApiKey(): ChannelFlowConnection {
		val key = apiKey.ifBlank { ChannelFlowUrls.extractApiKey(m3uUrl) }
			.ifBlank { ChannelFlowUrls.extractApiKey(epgUrl) }
			.ifBlank { local?.m3uUrl?.let(ChannelFlowUrls::extractApiKey).orEmpty() }
			.ifBlank { public?.m3uUrl?.let(ChannelFlowUrls::extractApiKey).orEmpty() }
		return if (key == apiKey) this else copy(apiKey = key)
	}

	fun withApiKey(newKey: String): ChannelFlowConnection {
		val key = newKey.trim()
		if (key.isBlank() || key == apiKey) return this
		return copy(
			apiKey = key,
			m3uUrl = ChannelFlowUrls.withApiKey(m3uUrl, key),
			epgUrl = ChannelFlowUrls.withApiKey(epgUrl, key),
			local = local?.withApiKey(key),
			public = public?.withApiKey(key),
		)
	}

	companion object {
		fun fromPinPayload(payload: ChannelFlowPinCrypto.Payload): ChannelFlowConnection {
			if (payload.m3u.isBlank() || payload.xmltv.isBlank()) {
				error("Pin payload did not include M3U and XMLTV URLs")
			}
			fun endpoint(m3u: String, xmltv: String): ChannelFlowEndpoint? {
				if (m3u.isBlank() || xmltv.isBlank()) return null
				return ChannelFlowEndpoint(
					baseUrl = ChannelFlowUrls.baseUrlFromLiveTvUrl(m3u),
					m3uUrl = m3u,
					epgUrl = xmltv,
				)
			}
			return ChannelFlowConnection(
				baseUrl = ChannelFlowUrls.baseUrlFromLiveTvUrl(payload.m3u),
				m3uUrl = payload.m3u,
				epgUrl = payload.xmltv,
				apiKey = ChannelFlowUrls.extractApiKey(payload.m3u)
					.ifBlank { ChannelFlowUrls.extractApiKey(payload.xmltv) },
				local = endpoint(payload.m3uLocal, payload.xmltvLocal),
				public = endpoint(payload.m3uPublic, payload.xmltvPublic),
			)
		}

		internal fun hostLabel(url: String): String {
			val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
			val host = uri.host?.removePrefix("www.").orEmpty()
			if (host.isBlank()) return url
			val port = uri.port
			return if (port != -1) "$host:$port" else host
		}
	}
}

@Serializable
data class ChannelFlowSavedServer(
	val id: String,
	val connection: ChannelFlowConnection,
)
