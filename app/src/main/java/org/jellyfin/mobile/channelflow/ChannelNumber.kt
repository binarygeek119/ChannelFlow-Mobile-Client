package org.jellyfin.mobile.channelflow

data class ChannelNumber(
	val major: Int,
	val minor: Int,
) : Comparable<ChannelNumber> {
	override fun compareTo(other: ChannelNumber): Int =
		compareValuesBy(this, other, { it.major }, { it.minor })

	companion object {
		fun parse(raw: String?): ChannelNumber? {
			if (raw.isNullOrBlank()) return null
			val value = raw.trim()
			val separator = value.indexOfFirst { it == '.' || it == '-' }
			if (separator < 0) {
				val major = value.filter { it.isDigit() }.toIntOrNull() ?: return null
				return ChannelNumber(major, 0)
			}
			val major = value.substring(0, separator).filter { it.isDigit() }.toIntOrNull() ?: return null
			val minor = value.substring(separator + 1).filter { it.isDigit() }.toIntOrNull() ?: 0
			return ChannelNumber(major, minor)
		}

		fun compare(left: String?, right: String?): Int {
			val a = parse(left)
			val b = parse(right)
			return when {
				a == null && b == null -> 0
				a == null -> 1
				b == null -> -1
				else -> a.compareTo(b)
			}
		}
	}
}
