package org.jellyfin.mobile.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

class M3uParserTests : FunSpec({
	test("parses ChannelFlow playlist entries") {
		val playlist = """
			#EXTM3U
			#EXTINF:-1 tvg-id="11111111222233334444555555555555" tvg-chno="5.1" tvg-name="News",News
			http://server/iptv/stream/11111111222233334444555555555555?apiKey=secret
		""".trimIndent()

		val channels = M3uParser.parse(playlist)
		channels.shouldHaveSize(1)
		channels[0].name shouldBe "News"
		channels[0].number shouldBe "5.1"
		channels[0].streamUrl.shouldStartWith("http://server/iptv/stream/")
	}

	test("skips VLC option lines and still finds the stream URL") {
		val playlist = """
			#EXTM3U
			#EXTINF:-1 tvg-name="Movie",Movie
			#EXTVLCOPT:http-user-agent=VLC
			https://cdn.example/live/index.m3u8
		""".trimIndent()

		val channels = M3uParser.parse(playlist)
		channels.shouldHaveSize(1)
		channels[0].streamUrl shouldBe "https://cdn.example/live/index.m3u8"
	}



	test("detects HLS vs MPEG-TS mime types") {
		ChannelFlowStream.mimeType("https://x/live/index.m3u8") shouldBe ChannelFlowStream.MIME_HLS
		ChannelFlowStream.mimeType("http://server/iptv/stream/abc") shouldBe ChannelFlowStream.MIME_TS
		ChannelFlowStream.container("https://x/live/index.m3u8") shouldBe "hls"
	}
})
