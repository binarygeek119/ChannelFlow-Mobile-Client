package org.jellyfin.mobile.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class ChannelFlowConnectionPersistenceTests : FunSpec({
	val connection = ChannelFlowConnection(
		baseUrl = "http://10.0.0.8:8096",
		m3uUrl = "http://10.0.0.8:8096/iptv/channels.m3u",
		epgUrl = "http://10.0.0.8:8096/iptv/xmltv.xml",
		apiKey = "secret-key",
	)

	test("restores the current multi-server store") {
		val saved = ChannelFlowSavedServer(id = "server-1", connection = connection)
		val encoded = ChannelFlowConnectionPersistence.encode(
			ChannelFlowServersState(
				servers = listOf(saved),
				activeServerId = saved.id,
				connection = connection,
			)
		)

		val decoded = ChannelFlowConnectionPersistence.decode(encoded).shouldNotBeNull()
		decoded.connection shouldBe connection
		decoded.activeServerId shouldBe "server-1"
		decoded.servers.single().id shouldBe "server-1"
	}

	test("restores the original single-connection store") {
		val encoded = """
			{"connection":{"baseUrl":"http://10.0.0.8:8096","m3uUrl":"http://10.0.0.8:8096/iptv/channels.m3u","epgUrl":"http://10.0.0.8:8096/iptv/xmltv.xml","apiKey":"secret-key"},"favoriteChannelIds":[]}
		""".trimIndent()

		val decoded = ChannelFlowConnectionPersistence.decode(encoded).shouldNotBeNull()
		decoded.connection shouldBe connection
		decoded.servers.shouldHaveSize(1)
	}

	test("restores a bare connection object") {
		val encoded = """
			{"baseUrl":"http://10.0.0.8:8096","m3uUrl":"http://10.0.0.8:8096/iptv/channels.m3u","epgUrl":"http://10.0.0.8:8096/iptv/xmltv.xml","apiKey":"secret-key"}
		""".trimIndent()

		val decoded = ChannelFlowConnectionPersistence.decode(encoded).shouldNotBeNull()
		decoded.connection shouldBe connection
	}

	test("recovers an API key left on the M3U URL") {
		val encoded = """
			{"connection":{"baseUrl":"http://10.0.0.8:8096","m3uUrl":"http://10.0.0.8:8096/iptv/channels.m3u?apiKey=secret-key","epgUrl":"http://10.0.0.8:8096/iptv/epg.xml","apiKey":""},"favoriteChannelIds":[]}
		""".trimIndent()

		val decoded = ChannelFlowConnectionPersistence.decode(encoded).shouldNotBeNull()
		decoded.connection?.apiKey shouldBe "secret-key"
	}

	test("does not treat corrupt json as an empty server list") {
		ChannelFlowConnectionPersistence.decode("{not-json") shouldBe null
		ChannelFlowConnectionPersistence.decode("") shouldBe null
		ChannelFlowConnectionPersistence.decode("   ") shouldBe null
	}
})
