package org.jellyfin.mobile.channelflow

import android.net.Uri
import java.net.URLDecoder
import java.net.URLEncoder

object ChannelFlowUrls {
	fun normalizeServerUrl(input: String): String? {
		var value = input.trim()
		if (value.isBlank()) return null
		if (!value.contains("://")) value = "https://$value"

		val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
		if (uri.host.isNullOrBlank()) return null

		val path = uri.path.orEmpty().trimEnd('/')
		val cleanedPath = when {
			path.equals("/pair", ignoreCase = true) -> ""
			path.endsWith("/pair.html", ignoreCase = true) -> path.removeSuffix("/pair.html")
			else -> path
		}.trimEnd('/')

		return buildString {
			append(uri.scheme)
			append("://")
			append(uri.authority)
			append(cleanedPath)
		}
	}

	fun extractApiKey(url: String): String {
		val query = url.substringAfter('?', "").substringBefore('#')
		if (query.isEmpty()) return ""
		return query.split('&').firstNotNullOfOrNull { part ->
			val name = part.substringBefore('=', missingDelimiterValue = part)
			if (!name.equals("apiKey", ignoreCase = true)) null
			else decodeQuery(part.substringAfter('=', missingDelimiterValue = ""))
		}.orEmpty()
	}

	fun withApiKey(url: String, apiKey: String): String {
		if (apiKey.isBlank() || url.isBlank()) return url
		val hash = url.indexOf('#')
		val beforeHash = if (hash >= 0) url.substring(0, hash) else url
		val fragment = if (hash >= 0) url.substring(hash) else ""
		val q = beforeHash.indexOf('?')
		val base = if (q >= 0) beforeHash.substring(0, q) else beforeHash
		val query = if (q >= 0) beforeHash.substring(q + 1) else ""
		val kept = query.split('&').filter { part ->
			part.isNotEmpty() && !part.substringBefore('=').equals("apiKey", ignoreCase = true)
		}
		val next = kept + ("apiKey=" + encodeQuery(apiKey))
		return base + "?" + next.joinToString("&") + fragment
	}

	fun sessionUrl(baseUrl: String): String =
		"${baseUrl.trimEnd('/')}/api/clients/session"

	fun revokeUrl(baseUrl: String): String =
		"${baseUrl.trimEnd('/')}/api/clients/me"

	fun baseUrlFromLiveTvUrl(url: String): String {
		val uri = Uri.parse(url)
		val path = uri.path.orEmpty().replace(Regex("/iptv/(channels\\.m3u|epg\\.xml)$", RegexOption.IGNORE_CASE), "")
		return buildString {
			append(uri.scheme ?: "http")
			append("://")
			append(uri.authority.orEmpty())
			append(path.trimEnd('/'))
		}
	}

	fun clientLogsUrl(baseUrl: String): String = ChannelFlowClientLogs.ingestUrl(baseUrl)

	private fun encodeQuery(value: String): String =
		URLEncoder.encode(value, "UTF-8").replace("+", "%20")

	private fun decodeQuery(value: String): String =
		URLDecoder.decode(value.replace("+", "%20"), "UTF-8")
}
