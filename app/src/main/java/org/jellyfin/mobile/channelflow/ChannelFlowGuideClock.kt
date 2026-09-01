package org.jellyfin.mobile.channelflow

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

object ChannelFlowGuideWindow {
	const val HOURS = 6L
	const val SLOT_MINUTES = 30L

	fun start(now: LocalDateTime = ChannelFlowGuideClock.now()): LocalDateTime {
		val minute = (now.minute / SLOT_MINUTES.toInt()) * SLOT_MINUTES.toInt()
		return now.truncatedTo(ChronoUnit.HOURS).plusMinutes(minute.toLong())
	}

	fun end(start: LocalDateTime = start()): LocalDateTime = start.plusHours(HOURS)

	fun minutes(): Long = HOURS * 60L
}

object ChannelFlowGuideClock {
	@Volatile
	private var coverageStart: LocalDateTime? = null

	@Volatile
	private var coverageEnd: LocalDateTime? = null

	fun updateCoverage(programs: List<ChannelFlowProgram>) {
		if (programs.isEmpty()) {
			coverageStart = null
			coverageEnd = null
			return
		}
		coverageStart = programs.minOf { it.start }
		coverageEnd = programs.maxOf { it.end }
	}

	fun now(): LocalDateTime = effectiveNow(LocalDateTime.now(), coverageStart, coverageEnd)

	fun effectiveNow(
		deviceNow: LocalDateTime,
		earliestStart: LocalDateTime?,
		latestEnd: LocalDateTime?,
	): LocalDateTime {
		if (earliestStart == null || latestEnd == null) return deviceNow
		if (deviceNow.isBefore(earliestStart)) return earliestStart
		if (!deviceNow.isBefore(latestEnd)) {
			return latestEnd.minusMinutes(1).let { if (it.isBefore(earliestStart)) earliestStart else it }
		}
		return deviceNow
	}
}
