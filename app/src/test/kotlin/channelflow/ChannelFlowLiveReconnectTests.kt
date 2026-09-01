package org.jellyfin.mobile.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ChannelFlowLiveReconnectTests : FunSpec({
	test("backs off then caps reconnect delay") {
		ChannelFlowLiveReconnect.delayMs(0) shouldBe 400L
		ChannelFlowLiveReconnect.delayMs(1) shouldBe 800L
		ChannelFlowLiveReconnect.delayMs(2) shouldBe 1_600L
		ChannelFlowLiveReconnect.delayMs(3) shouldBe 3_200L
		ChannelFlowLiveReconnect.delayMs(4) shouldBe 5_000L
		ChannelFlowLiveReconnect.delayMs(9) shouldBe 5_000L
	}

	test("keeps hardware decode for the first few live recoveries") {
		ChannelFlowLiveReconnect.preferHardware(0) shouldBe true
		ChannelFlowLiveReconnect.preferHardware(2) shouldBe true
		ChannelFlowLiveReconnect.preferHardware(3) shouldBe false
	}

	test("replaces cfLive so VLC cannot resume the flushed socket") {
		val first = ChannelFlowLiveReconnect.withCacheBust(
			"http://10.0.0.8:8096/iptv/stream.ts?apiKey=k",
			1_000L,
		)
		first shouldContain "apiKey=k"
		first shouldContain "cfLive=1000"
		val next = ChannelFlowLiveReconnect.withCacheBust(first, 2_000L)
		next shouldContain "cfLive=2000"
		next.shouldNotContain("cfLive=1000")
	}

	test("treats a frozen clock or empty buffer as a stall after the live timeout") {
		ChannelFlowLiveReconnect.isStalled(
			nowMs = 10_000L,
			lastProgressMs = 5_000L,
			awaitingFirstFrame = false,
			sawTimeAdvance = true,
			bufferingPercent = 100f,
		) shouldBe true
		ChannelFlowLiveReconnect.isStalled(
			nowMs = 10_000L,
			lastProgressMs = 9_000L,
			awaitingFirstFrame = false,
			sawTimeAdvance = true,
			bufferingPercent = 100f,
		) shouldBe false
		ChannelFlowLiveReconnect.isStalled(
			nowMs = 10_000L,
			lastProgressMs = 1_000L,
			awaitingFirstFrame = true,
			sawTimeAdvance = false,
			bufferingPercent = 0f,
		) shouldBe true
	}
})
