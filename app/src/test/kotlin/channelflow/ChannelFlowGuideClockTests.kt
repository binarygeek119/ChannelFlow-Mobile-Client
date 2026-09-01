package org.jellyfin.mobile.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class ChannelFlowGuideClockTests : FunSpec({
	val start = LocalDateTime.of(2026, 8, 28, 5, 0)
	val end = LocalDateTime.of(2026, 8, 29, 5, 0)

	test("uses device clock when it falls inside XMLTV coverage") {
		val now = LocalDateTime.of(2026, 8, 28, 8, 45)
		ChannelFlowGuideClock.effectiveNow(now, start, end) shouldBe now
	}

	test("pins to coverage start when the device clock is behind XMLTV") {
		val now = LocalDateTime.of(2026, 8, 26, 22, 0)
		ChannelFlowGuideClock.effectiveNow(now, start, end) shouldBe start
	}

	test("pins near coverage end when the device clock is past XMLTV") {
		val now = LocalDateTime.of(2026, 8, 30, 12, 0)
		ChannelFlowGuideClock.effectiveNow(now, start, end) shouldBe end.minusMinutes(1)
	}

	test("keeps the device clock when coverage is empty") {
		val now = LocalDateTime.of(2026, 8, 26, 22, 0)
		ChannelFlowGuideClock.effectiveNow(now, null, null) shouldBe now
	}

	test("snaps the grid window to the current half hour") {
		val now = LocalDateTime.of(2026, 8, 31, 22, 17)
		val start = ChannelFlowGuideWindow.start(now)
		start shouldBe LocalDateTime.of(2026, 8, 31, 22, 0)
		ChannelFlowGuideWindow.end(start) shouldBe start.plusHours(6)
	}
})
