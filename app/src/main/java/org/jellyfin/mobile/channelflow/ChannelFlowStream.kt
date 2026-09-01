package org.jellyfin.mobile.channelflow

object ChannelFlowStream {
	const val MIME_HLS = "application/x-mpegURL"
	const val MIME_DASH = "application/dash+xml"
	const val MIME_TS = "video/mp2t"
	const val MIME_MP4 = "video/mp4"
	const val MIME_MKV = "video/x-matroska"
	const val MIME_WEBM = "video/webm"

	fun path(url: String): String = url.substringBefore('#').substringBefore('?').lowercase()

	fun isHls(url: String): Boolean {
		val value = path(url)
		return value.contains(".m3u8") || value.endsWith(".m3u")
	}

	fun isMpegTs(url: String): Boolean {
		val value = path(url)
		return value.contains("/iptv/stream/") ||
			value.endsWith(".ts") ||
			value.endsWith(".m2ts") ||
			value.endsWith(".mts")
	}

	fun mimeType(url: String): String {
		val value = path(url)
		return when {
			isHls(url) -> MIME_HLS
			value.contains(".mpd") -> MIME_DASH
			value.endsWith(".mp4") || value.endsWith(".m4v") -> MIME_MP4
			value.endsWith(".mkv") -> MIME_MKV
			value.endsWith(".webm") -> MIME_WEBM
			else -> MIME_TS
		}
	}

	fun container(url: String): String = when {
		isHls(url) -> "hls"
		path(url).contains(".mpd") -> "mpd"
		path(url).endsWith(".mp4") || path(url).endsWith(".m4v") -> "mp4"
		path(url).endsWith(".mkv") -> "mkv"
		path(url).endsWith(".webm") -> "webm"
		else -> "ts"
	}

	fun isLive(url: String): Boolean {
		val value = path(url)
		return isHls(url) || isMpegTs(url) || value.contains("/iptv/") || value.contains("/live")
	}

	fun redact(url: String): String =
		url.replace(Regex("(?i)((?:api[_-]?key|token|password|auth)=)[^&]*"), "$1***")
}
