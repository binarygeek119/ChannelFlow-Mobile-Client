package org.jellyfin.mobile.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength

class ChannelFlowClientLogsTests : FunSpec({
	test("builds the ingest URL from a ChannelFlow-Server base") {
		ChannelFlowClientLogs.ingestUrl("https://tv.example.com") shouldBe "https://tv.example.com/api/client-logs"
		ChannelFlowClientLogs.ingestUrl("http://10.0.0.8:8097/") shouldBe "http://10.0.0.8:8097/api/client-logs"
	}

	test("keeps a safe device id") {
		ChannelFlowClientLogs.sanitizeDeviceId(" ab:cd/ef ") shouldBe "abcdef"
		ChannelFlowClientLogs.sanitizeDeviceId("living-room_1") shouldBe "living-room_1"
		ChannelFlowClientLogs.sanitizeDeviceId("   ") shouldBe null
		ChannelFlowClientLogs.sanitizeDeviceId("x".repeat(80)).shouldHaveLength(64)
	}

	test("maps android log priorities") {
		ChannelFlowClientLogs.levelName(2) shouldBe "verbose"
		ChannelFlowClientLogs.levelName(3) shouldBe "debug"
		ChannelFlowClientLogs.levelName(4) shouldBe "info"
		ChannelFlowClientLogs.levelName(5) shouldBe "warn"
		ChannelFlowClientLogs.levelName(6) shouldBe "error"
		ChannelFlowClientLogs.levelName(7) shouldBe "assert"
	}

	test("redacts api keys in stream URLs and log text") {
		val url = "https://tv.example.com/iptv/stream/abc?apiKey=super-secret-key"
		ChannelFlowClientLogs.redactSecrets(url) shouldBe "https://tv.example.com/iptv/stream/abc?apiKey=***"
		ChannelFlowClientLogs.sanitizeText("X-Api-Key: super-secret-key", 200) shouldBe "X-Api-Key: ***"
	}
})
