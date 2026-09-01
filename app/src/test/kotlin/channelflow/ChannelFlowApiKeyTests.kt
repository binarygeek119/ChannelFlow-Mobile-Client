package org.jellyfin.mobile.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ChannelFlowApiKeyTests : FunSpec({
	val connection = ChannelFlowConnection(
		baseUrl = "http://10.0.0.8:8096",
		m3uUrl = "http://10.0.0.8:8096/iptv/channels.m3u?apiKey=plugin-key",
		epgUrl = "http://10.0.0.8:8096/iptv/epg.xml?apiKey=plugin-key",
		apiKey = "plugin-key",
	)

	test("rewrites live TV URLs onto a unique API key") {
		val next = connection.withApiKey("unique-tv-key")
		next.apiKey shouldBe "unique-tv-key"
		next.m3uUrl shouldContain "apiKey=unique-tv-key"
		next.m3uUrl.shouldNotContain("plugin-key")
		next.epgUrl shouldContain "apiKey=unique-tv-key"
		next.epgUrl.shouldNotContain("plugin-key")
	}

	test("leaves the connection alone when the key is unchanged") {
		connection.withApiKey("plugin-key") shouldBe connection
	}

	test("builds session and revoke URLs from the server origin") {
		ChannelFlowUrls.sessionUrl("http://10.0.0.8:8096/") shouldBe
			"http://10.0.0.8:8096/api/clients/session"
		ChannelFlowUrls.revokeUrl("http://10.0.0.8:8096") shouldBe
			"http://10.0.0.8:8096/api/clients/me"
	}

	test("replaces an existing apiKey query parameter") {
		val rewritten = ChannelFlowUrls.withApiKey(
			"http://10.0.0.8:8096/iptv/channels.m3u?apiKey=old&foo=1",
			"new-key",
		)
		rewritten shouldContain "apiKey=new-key"
		rewritten shouldContain "foo=1"
		rewritten.shouldNotContain("old")
		ChannelFlowUrls.extractApiKey(rewritten) shouldBe "new-key"
	}

	test("does not treat two different keys as the same connection") {
		connection.withApiKey("other-key").apiKey shouldNotBe connection.apiKey
	}
})
