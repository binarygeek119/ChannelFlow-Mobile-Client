package org.jellyfin.mobile.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.util.UUID

class ChannelZapTests : FunSpec({
	val now = LocalDateTime.of(2026, 8, 31, 21, 0)
	val ch1 = channel(1)
	val ch2 = channel(2)
	val ch3 = channel(3)
	val ch4 = channel(4)
	val lineup = listOf(ch1, ch2, ch3, ch4)

	test("moves one channel when the neighbor is airing") {
		val programs = listOf(live(ch1), live(ch2), live(ch3), live(ch4))
		nextChannelWithContent(lineup, programs, now, ch2.id, higher = true)?.id shouldBe ch3.id
		nextChannelWithContent(lineup, programs, now, ch2.id, higher = false)?.id shouldBe ch1.id
	}

	test("skips empty neighbors to the next channel with content") {
		val programs = listOf(live(ch1), live(ch4))
		nextChannelWithContent(lineup, programs, now, ch1.id, higher = true)?.id shouldBe ch4.id
		nextChannelWithContent(lineup, programs, now, ch4.id, higher = false)?.id shouldBe ch1.id
	}

	test("wraps around empty channels at the ends") {
		val programs = listOf(live(ch2), live(ch3))
		nextChannelWithContent(lineup, programs, now, ch3.id, higher = true)?.id shouldBe ch2.id
		nextChannelWithContent(lineup, programs, now, ch2.id, higher = false)?.id shouldBe ch3.id
	}

	test("still moves one channel when nothing is airing") {
		nextChannelWithContent(lineup, emptyList(), now, ch2.id, higher = true)?.id shouldBe ch3.id
		nextChannelWithContent(lineup, emptyList(), now, ch2.id, higher = false)?.id shouldBe ch1.id
	}
})

private fun channel(number: Int) = ChannelFlowChannel(
	id = UUID.fromString("00000000-0000-0000-0000-00000000000$number"),
	name = "Channel $number",
	number = number.toString(),
	logoUrl = null,
	streamUrl = "https://example.com/$number.m3u8",
)

private fun live(channel: ChannelFlowChannel, now: LocalDateTime = LocalDateTime.of(2026, 8, 31, 21, 0)) =
	ChannelFlowProgram(
		id = UUID.randomUUID(),
		channelId = channel.id,
		title = channel.name,
		episodeTitle = null,
		overview = null,
		start = now.minusMinutes(10),
		end = now.plusMinutes(50),
		iconUrl = null,
		categories = emptyList(),
		officialRating = null,
		productionYear = null,
	)
