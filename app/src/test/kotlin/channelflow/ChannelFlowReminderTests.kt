package org.jellyfin.mobile.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ChannelFlowReminderTests : FunSpec({
	test("fires after the program start plus padding") {
		val start = 1_000_000L
		ChannelFlowReminder.fireAt(start) shouldBe start + 20_000L
		ChannelFlowReminder.fireAt(start, 45) shouldBe start + 45_000L
	}

	test("is due only after padding and before the program ends") {
		val reminder = ChannelFlowReminder(
			programId = "11111111-1111-1111-1111-111111111111",
			channelId = "22222222-2222-2222-2222-222222222222",
			title = "Show",
			startEpochMilli = 1_000_000L,
			endEpochMilli = 1_200_000L,
		)
		reminder.isDue(1_019_999L) shouldBe false
		reminder.isDue(1_020_000L) shouldBe true
		reminder.isExpired(1_200_001L) shouldBe true
		reminder.isDue(1_200_001L) shouldBe false
	}
})
