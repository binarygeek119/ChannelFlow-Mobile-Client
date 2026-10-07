package org.jellyfin.mobile.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

class ChannelFlowEndpointTests : FunSpec({
	val json = Json { ignoreUnknownKeys = true }
	val local = ChannelFlowEndpoint(
		baseUrl = "http://192.168.1.10:8096",
		m3uUrl = "http://192.168.1.10:8096/iptv/channels.m3u?apiKey=plugin-key",
		epgUrl = "http://192.168.1.10:8096/iptv/epg.xml?apiKey=plugin-key",
	)
	val public = ChannelFlowEndpoint(
		baseUrl = "https://tv.example/flow",
		m3uUrl = "https://tv.example/flow/iptv/channels.m3u?apiKey=plugin-key",
		epgUrl = "https://tv.example/flow/iptv/epg.xml?apiKey=plugin-key",
	)
	val connection = ChannelFlowConnection(
		baseUrl = local.baseUrl,
		m3uUrl = local.m3uUrl,
		epgUrl = local.epgUrl,
		apiKey = "plugin-key",
		local = local,
		public = public,
	)

	test("legacy pin payload keeps variant URLs empty") {
		val payload = json.decodeFromString(
			ChannelFlowPinCrypto.Payload.serializer(),
			"""{"m3u":"http://192.168.1.10:8096/iptv/channels.m3u","xmltv":"http://192.168.1.10:8096/iptv/epg.xml"}""",
		)
		payload.m3u shouldBe "http://192.168.1.10:8096/iptv/channels.m3u"
		payload.xmltv shouldBe "http://192.168.1.10:8096/iptv/epg.xml"
		payload.m3uPublic shouldBe ""
		payload.xmltvPublic shouldBe ""
		payload.m3uLocal shouldBe ""
		payload.xmltvLocal shouldBe ""
	}

	test("six-key pin payload keeps public and local variants") {
		val payload = json.decodeFromString(
			ChannelFlowPinCrypto.Payload.serializer(),
			"""
				{
					"m3u":"http://192.168.1.10:8096/iptv/channels.m3u",
					"xmltv":"http://192.168.1.10:8096/iptv/epg.xml",
					"m3uPublic":"https://tv.example/flow/iptv/channels.m3u",
					"xmltvPublic":"https://tv.example/flow/iptv/epg.xml",
					"m3uLocal":"http://192.168.1.10:8096/iptv/channels.m3u",
					"xmltvLocal":"http://192.168.1.10:8096/iptv/epg.xml"
				}
			""".trimIndent(),
		)
		payload.m3uPublic shouldBe "https://tv.example/flow/iptv/channels.m3u"
		payload.xmltvLocal shouldBe "http://192.168.1.10:8096/iptv/epg.xml"
	}

	test("rewrites live TV URLs onto a unique API key including variants") {
		val next = connection.withApiKey("unique-tv-key")
		next.apiKey shouldBe "unique-tv-key"
		next.m3uUrl shouldContain "apiKey=unique-tv-key"
		next.epgUrl.shouldNotContain("plugin-key")
		next.local?.m3uUrl.orEmpty() shouldContain "apiKey=unique-tv-key"
		next.public?.epgUrl.orEmpty() shouldContain "apiKey=unique-tv-key"
		next.public?.m3uUrl.orEmpty().shouldNotContain("plugin-key")
	}

	test("treats LAN and WAN URLs as the same saved server") {
		val fromWan = connection.copy(
			baseUrl = public.baseUrl,
			m3uUrl = public.m3uUrl,
			epgUrl = public.epgUrl,
		)
		connection.sharesServer(fromWan) shouldBe true
		connection.sharesServer(
			ChannelFlowConnection(
				baseUrl = "http://10.0.0.8:8096",
				m3uUrl = "http://10.0.0.8:8096/iptv/channels.m3u",
				epgUrl = "http://10.0.0.8:8096/iptv/epg.xml",
				apiKey = "other",
			),
		) shouldBe false
	}

	test("rewrites public stream URLs onto the local base including a path prefix") {
		ChannelFlowUrls.rewriteToward(
			"https://tv.example/flow/iptv/stream/abc?apiKey=x",
			listOf(public.baseUrl, local.baseUrl),
			local.baseUrl,
		) shouldBe "http://192.168.1.10:8096/iptv/stream/abc?apiKey=x"
	}

	test("rewrites local stream URLs onto the public base") {
		ChannelFlowUrls.rewriteToward(
			"http://192.168.1.10:8096/iptv/stream/abc",
			connection.knownBaseUrls(),
			public.baseUrl,
		) shouldBe "https://tv.example/flow/iptv/stream/abc"
	}

	test("leaves unrelated CDN URLs alone") {
		ChannelFlowUrls.rewriteToward(
			"https://cdn.example/live/index.m3u8",
			connection.knownBaseUrls(),
			local.baseUrl,
		) shouldBe "https://cdn.example/live/index.m3u8"
	}

	test("detects the same IPv4 subnet") {
		ChannelFlowNetworkMatch.sameSubnet(
			"192.168.1.10",
			listOf(address(192, 168, 1, 42, 24)),
		) shouldBe true
	}

	test("detects a different IPv4 subnet") {
		ChannelFlowNetworkMatch.sameSubnet(
			"10.0.0.8",
			listOf(address(192, 168, 1, 42, 24)),
		) shouldBe false
	}

	test("is inconclusive for hostnames") {
		ChannelFlowNetworkMatch.sameSubnet(
			"channelflow.local",
			listOf(address(192, 168, 1, 42, 24)),
		).shouldBeNull()
	}

	test("does not match when the device has no address in that family") {
		ChannelFlowNetworkMatch.sameSubnet("192.168.1.10", emptyList()) shouldBe false
	}

	test("matches a /16 prefix") {
		ChannelFlowNetworkMatch.sameSubnet(
			"192.168.50.10",
			listOf(address(192, 168, 1, 42, 16)),
		) shouldBe true
	}

	test("requires an exact match on /32") {
		ChannelFlowNetworkMatch.sameSubnet(
			"192.168.1.10",
			listOf(address(192, 168, 1, 42, 32)),
		) shouldBe false
		ChannelFlowNetworkMatch.sameSubnet(
			"192.168.1.10",
			listOf(address(192, 168, 1, 10, 32)),
		) shouldBe true
	}

	test("round-trips public and local endpoints in the store") {
		val encoded = ChannelFlowConnectionPersistence.encode(
			ChannelFlowServersState(connection = connection),
		)
		encoded shouldContain "\"local\""
		encoded shouldContain "\"public\""
		val decoded = ChannelFlowConnectionPersistence.decode(encoded)
		decoded?.connection shouldBe connection
		decoded?.connection?.local shouldNotBe null
		decoded?.connection?.public?.baseUrl shouldBe public.baseUrl
	}
})

private fun address(a: Int, b: Int, c: Int, d: Int, prefix: Int) =
	ChannelFlowNetworkMatch.DeviceAddress(
		bytes = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()),
		prefixLength = prefix,
	)
