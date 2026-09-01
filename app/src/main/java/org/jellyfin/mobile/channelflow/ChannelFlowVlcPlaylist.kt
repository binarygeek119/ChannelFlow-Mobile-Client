package org.jellyfin.mobile.channelflow

import java.io.File
import java.util.UUID

object ChannelFlowVlcPlaylist {
	const val USER_AGENT = "ChannelFlow-Mobile"
	/** Jitter buffer before playback starts (ms). Keep short so zapping stays snappy. */
	const val START_CACHE_MS = 1500
	/**
	 * Background prefetch cap in KiB (~10 minutes at 8 Mbps IPTV HD).
	 * VLC fills this as fast as the server will send, then holds until playback consumes it.
	 */
	const val PREFETCH_BUFFER_KIB = 614_400
	const val PREFETCH_READ_BYTES = 1_048_576

	fun text(
		streamUrl: String,
		name: String = "ChannelFlow",
		channelId: UUID? = null,
		number: String? = null,
		logoUrl: String? = null,
		apiKey: String? = null,
	): String {
		val title = escape(name.ifBlank { "ChannelFlow" })
		val playUrl = withApiKey(streamUrl.trim(), apiKey)
		val extinf = buildString {
			append("#EXTINF:-1")
			if (channelId != null) append(" tvg-id=\"").append(channelId.toString().replace("-", "")).append('"')
			if (!number.isNullOrBlank()) append(" tvg-chno=\"").append(escape(number)).append('"')
			append(" tvg-name=\"").append(title).append('"')
			if (!logoUrl.isNullOrBlank()) append(" tvg-logo=\"").append(escape(logoUrl)).append('"')
			append(',').append(title)
		}
		return buildString {
			appendLine("#EXTM3U")
			appendLine(extinf)
			appendLine("#EXTVLCOPT:http-user-agent=$USER_AGENT")
			appendLine("#EXTVLCOPT:network-caching=$START_CACHE_MS")
			appendLine("#EXTVLCOPT:live-caching=$START_CACHE_MS")
			appendLine("#EXTVLCOPT:stream-filter=prefetch")
			appendLine("#EXTVLCOPT:prefetch-buffer-size=$PREFETCH_BUFFER_KIB")
			appendLine("#EXTVLCOPT:prefetch-read-size=$PREFETCH_READ_BYTES")
			appendLine("#EXTVLCOPT:http-reconnect=true")
			if (!apiKey.isNullOrBlank()) appendLine("#EXTVLCOPT:http-header=X-Api-Key: $apiKey")
			appendLine(playUrl)
		}
	}

	fun write(
		file: File,
		streamUrl: String,
		name: String = "ChannelFlow",
		channelId: UUID? = null,
		number: String? = null,
		logoUrl: String? = null,
		apiKey: String? = null,
	): File {
		file.parentFile?.mkdirs()
		file.writeText(text(streamUrl, name, channelId, number, logoUrl, apiKey), Charsets.UTF_8)
		return file
	}

	fun streamUrlFrom(text: String): String? =
		M3uParser.parse(text).firstOrNull()?.streamUrl

	fun withApiKey(url: String, apiKey: String?): String =
		ChannelFlowUrls.withApiKey(url, apiKey.orEmpty())

	private fun escape(value: String): String =
		value.replace(',', ' ').replace('\n', ' ').replace('"', '\'')
}
