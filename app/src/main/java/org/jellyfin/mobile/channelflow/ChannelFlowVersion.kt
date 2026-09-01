package org.jellyfin.mobile.channelflow

object ChannelFlowVersion {
	fun normalize(raw: String): String =
		raw.trim().removePrefix("v").removePrefix("V").removePrefix(".")

	fun isNewer(latest: String, current: String): Boolean =
		rank(normalize(latest)) > rank(normalize(current))

	fun display(raw: String): String = "v.${normalize(raw)}"

	fun shouldPromptLaunch(latest: String, dismissed: String?): Boolean {
		if (normalize(latest).isBlank()) return false
		if (dismissed.isNullOrBlank()) return true
		return isNewer(latest, dismissed)
	}

	internal fun rank(version: String): Long {
		val (core, preRelease) = when (val dash = version.indexOf('-')) {
			-1 -> version to null
			else -> version.substring(0, dash) to version.substring(dash + 1)
		}
		val parts = core.split('.').mapNotNull { it.toIntOrNull() }
		val major = parts.getOrElse(0) { 0 }
		val minor = parts.getOrElse(1) { 0 }
		val patch = parts.getOrElse(2) { 0 }
		val build = preRelease
			?.substringAfter('.', preRelease)
			?.filter { it.isDigit() }
			?.toIntOrNull()
			?: if (preRelease == null) 99 else 0
		return major * 1_000_000L + minor * 10_000L + patch * 100L + build.coerceIn(0, 99)
	}
}
