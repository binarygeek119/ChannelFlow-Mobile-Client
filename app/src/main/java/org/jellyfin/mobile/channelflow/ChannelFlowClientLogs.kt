package org.jellyfin.mobile.channelflow

object ChannelFlowClientLogs {
	const val PATH = "/api/client-logs"
	const val MAX_BATCH = 80
	const val MAX_QUEUE = 400
	const val MAX_MESSAGE = 8_000
	const val MAX_EXCEPTION = 16_000
	const val MAX_DEVICE_ID = 64

	private val apiKeyQuery = Regex("([?&](?:apiKey|api_key)=)[^&\\s]+", RegexOption.IGNORE_CASE)
	private val apiKeyHeader = Regex("(X-Api-Key:\\s*)\\S+", RegexOption.IGNORE_CASE)

	fun ingestUrl(baseUrl: String): String =
		"${baseUrl.trimEnd('/')}$PATH"

	fun sanitizeDeviceId(raw: String?): String? {
		if (raw.isNullOrBlank()) return null
		val cleaned = raw.trim().filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
		if (cleaned.isEmpty()) return null
		return cleaned.take(MAX_DEVICE_ID)
	}

	fun sanitizeText(raw: String?, maxChars: Int): String? {
		if (raw.isNullOrBlank()) return null
		val redacted = redactSecrets(raw.trim())
		return if (redacted.length <= maxChars) redacted else redacted.take(maxChars)
	}

	@JvmStatic
	fun redactSecrets(raw: String): String =
		apiKeyQuery.replace(apiKeyHeader.replace(raw, "$1***"), "$1***")

	fun levelName(priority: Int): String = when (priority) {
		2 -> "verbose"
		3 -> "debug"
		5 -> "warn"
		6 -> "error"
		7 -> "assert"
		else -> "info"
	}
}
