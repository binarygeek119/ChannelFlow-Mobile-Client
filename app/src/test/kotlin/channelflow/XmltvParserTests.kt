package org.jellyfin.mobile.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class XmltvParserTests : FunSpec({
	test("parseTime reads ChannelFlow yyyyMMddHHmmss +0000") {
		val parsed = XmltvParser.parseTime("20260826233000 +0000").shouldNotBeNull()
		parsed shouldBe utcToLocal(2026, 8, 26, 23, 30)
	}

	test("parseTime reads offsets with colons") {
		val parsed = XmltvParser.parseTime("20260826233000 +00:00").shouldNotBeNull()
		parsed shouldBe utcToLocal(2026, 8, 26, 23, 30)
	}

	test("parseTime treats 14-digit stamps as UTC") {
		val parsed = XmltvParser.parseTime("20260826233000").shouldNotBeNull()
		parsed shouldBe utcToLocal(2026, 8, 26, 23, 30)
	}

	test("parse binds programmes to channel ids") {
		val channelId = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555")
		val xml = """
			<?xml version="1.0" encoding="UTF-8"?>
			<tv generator-info-name="ChannelFlow-Server">
				<channel id="11111111222233334444555555555555">
					<display-name>News</display-name>
				</channel>
				<programme start="20260826220000 +0000" stop="20260827000000 +0000" channel="11111111222233334444555555555555">
					<title>Evening News</title>
					<sub-title>World</sub-title>
					<desc>Headlines</desc>
					<category>News</category>
				</programme>
			</tv>
		""".trimIndent()

		val programs = XmltvParser.parse(xml)
		programs.shouldHaveSize(1)
		programs[0].channelId shouldBe channelId
		programs[0].title shouldBe "Evening News"
		programs[0].episodeTitle shouldBe "World"
		programs[0].start shouldBe utcToLocal(2026, 8, 26, 22, 0)
		programs[0].end shouldBe utcToLocal(2026, 8, 27, 0, 0)
	}
})

private fun utcToLocal(year: Int, month: Int, day: Int, hour: Int, minute: Int): LocalDateTime =
	OffsetDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC)
		.atZoneSameInstant(ZoneId.systemDefault())
		.toLocalDateTime()
