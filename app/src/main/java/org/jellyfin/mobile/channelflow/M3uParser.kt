package org.jellyfin.mobile.channelflow

import java.util.UUID

data class ChannelFlowChannel(
	val id: UUID,
	val name: String,
	val number: String?,
	val logoUrl: String?,
	val streamUrl: String,
) {
	fun hasPlayableStream(): Boolean = isPlayableStreamUrl(streamUrl)
}

internal fun isPlayableStreamUrl(url: String): Boolean {
	val value = url.trim()
	if (value.isEmpty()) return false
	val scheme = value.substringBefore(':', "").lowercase()
	return scheme == "http" || scheme == "https" || scheme == "udp" || scheme == "rtp" || scheme == "rtsp" || scheme == "rtmp"
}

object M3uParser {
	private val attribute = Regex("""([A-Za-z0-9-]+)="([^"]*)"""")

	fun parse(playlist: String): List<ChannelFlowChannel> {
		val channels = mutableListOf<ChannelFlowChannel>()
		val lines = playlist.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
		var index = 0
		while (index < lines.size) {
			val line = lines[index]
			if (!line.startsWith("#EXTINF", ignoreCase = true)) {
				index++
				continue
			}

			val comma = line.lastIndexOf(',')
			val attrs = if (comma >= 0) line.substring(0, comma) else line
			val displayName = if (comma >= 0) line.substring(comma + 1).trim() else ""
			val values = attribute.findAll(attrs).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

			var cursor = index + 1
			var url = ""
			while (cursor < lines.size) {
				val candidate = lines[cursor]
				cursor++
				if (candidate.startsWith("#")) continue
				url = candidate
				break
			}
			index = cursor
			if (!isPlayableStreamUrl(url)) continue

			val id = ChannelFlowIds.parse(values["tvg-id"])
				?: ChannelFlowIds.parse(values["channel-id"])
				?: UUID.nameUUIDFromBytes(url.toByteArray())
			channels += ChannelFlowChannel(
				id = id,
				name = values["tvg-name"]?.ifBlank { null } ?: displayName.ifBlank { id.toString() },
				number = values["tvg-chno"]?.ifBlank { null } ?: values["channel-number"]?.ifBlank { null },
				logoUrl = values["tvg-logo"]?.ifBlank { null },
				streamUrl = url.trim(),
			)
		}
		return channels
	}
}
