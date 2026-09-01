package org.jellyfin.mobile.channelflow

/**
 * Live HTTP recovery when ChannelFlow flushes the run-ahead ring for an EBS alert.
 * The socket stays open with a TS discontinuity, so VLC must open a fresh GET.
 */
object ChannelFlowLiveReconnect {
	const val STALL_MS = 4_000L
	const val OPEN_TIMEOUT_MS = 8_000L
	const val WATCHDOG_MS = 1_000L
	const val MIN_DELAY_MS = 400L
	const val MAX_DELAY_MS = 5_000L
	const val SOFTWARE_AFTER_FAILURES = 3

	fun delayMs(failures: Int): Long {
		val shift = failures.coerceIn(0, 4)
		return (MIN_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
	}

	fun preferHardware(failures: Int): Boolean = failures < SOFTWARE_AFTER_FAILURES

	fun withCacheBust(url: String, nowMs: Long): String {
		val hash = url.indexOf('#')
		val before = if (hash >= 0) url.substring(0, hash) else url
		val fragment = if (hash >= 0) url.substring(hash) else ""
		val q = before.indexOf('?')
		val base = if (q >= 0) before.substring(0, q) else before
		val query = if (q >= 0) before.substring(q + 1) else ""
		val kept = query.split('&').filter { part ->
			part.isNotEmpty() && !part.substringBefore('=').equals("cfLive", ignoreCase = true)
		}
		return base + "?" + (kept + "cfLive=$nowMs").joinToString("&") + fragment
	}

	fun isStalled(
		nowMs: Long,
		lastProgressMs: Long,
		awaitingFirstFrame: Boolean,
		sawTimeAdvance: Boolean,
		bufferingPercent: Float,
	): Boolean {
		val idle = nowMs - lastProgressMs
		if (awaitingFirstFrame) return idle >= OPEN_TIMEOUT_MS
		if (idle < STALL_MS) return false
		return sawTimeAdvance || bufferingPercent < 80f
	}
}
