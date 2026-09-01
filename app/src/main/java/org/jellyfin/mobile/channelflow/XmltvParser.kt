package org.jellyfin.mobile.channelflow

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ChannelFlowProgram(
	val id: UUID,
	val channelId: UUID,
	val title: String,
	val episodeTitle: String?,
	val overview: String?,
	val start: LocalDateTime,
	val end: LocalDateTime,
	val iconUrl: String?,
	val categories: List<String>,
	val officialRating: String?,
	val productionYear: Int?,
)

object XmltvParser {
	private val withoutOffset = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
	private val programmeBlock = Regex(
		"""<programme\b([^>]*)>(.*?)</programme>""",
		setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
	)
	private val titleTag = Regex("""<title\b[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
	private val subTitleTag = Regex("""<sub-title\b[^>]*>(.*?)</sub-title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
	private val descTag = Regex("""<desc\b[^>]*>(.*?)</desc>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
	private val categoryTag = Regex("""<category\b[^>]*>(.*?)</category>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
	private val iconSrc = Regex("""<icon\b[^>]*\bsrc\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
	private val dateTag = Regex("""<date\b[^>]*>(.*?)</date>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
	private val ratingValue = Regex("""<value\b[^>]*>(.*?)</value>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
	private val attr = Regex("""([A-Za-z_:][\w:.-]*)\s*=\s*"([^"]*)"""")

	fun parse(xml: String): List<ChannelFlowProgram> {
		val text = xml.trim().trimStart('\uFEFF')
		if (text.isBlank() || !text.contains("<programme", ignoreCase = true)) return emptyList()

		val fromXml = runCatching { parseWithPullParser(text) }.getOrDefault(emptyList())
		if (fromXml.isNotEmpty()) return fromXml
		return parseWithRegex(text)
	}

	private fun parseWithPullParser(text: String): List<ChannelFlowProgram> {
		val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
			runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
			setInput(StringReader(text))
		}

		val programs = mutableListOf<ChannelFlowProgram>()
		var event = parser.eventType
		while (event != XmlPullParser.END_DOCUMENT) {
			if (event == XmlPullParser.START_TAG && parser.name.equals("programme", ignoreCase = true)) {
				runCatching { parseProgramme(parser) }.getOrNull()?.let(programs::add)
			}
			event = parser.next()
		}
		return programs
	}

	private fun parseWithRegex(text: String): List<ChannelFlowProgram> {
		return programmeBlock.findAll(text).mapNotNull { match ->
			val attributes = attr.findAll(match.groupValues[1]).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
			val body = match.groupValues[2]
			val channelId = ChannelFlowIds.parse(attributes["channel"]) ?: return@mapNotNull null
			val start = parseTime(attributes["start"]) ?: return@mapNotNull null
			val end = parseTime(attributes["stop"]) ?: return@mapNotNull null
			if (!end.isAfter(start)) return@mapNotNull null
			val title = innerText(titleTag, body).ifBlank { "Program" }
			val id = UUID.nameUUIDFromBytes("$channelId|$start|$title".toByteArray())
			ChannelFlowProgram(
				id = id,
				channelId = channelId,
				title = title,
				episodeTitle = innerText(subTitleTag, body).ifBlank { null },
				overview = innerText(descTag, body).ifBlank { null },
				start = start,
				end = end,
				iconUrl = iconSrc.find(body)?.groupValues?.get(1)?.ifBlank { null },
				categories = categoryTag.findAll(body).map { decodeXml(it.groupValues[1]).trim() }.filter { it.isNotEmpty() }.toList(),
				officialRating = innerText(ratingValue, body).ifBlank { null },
				productionYear = innerText(dateTag, body).take(4).toIntOrNull(),
			)
		}.toList()
	}

	private fun innerText(pattern: Regex, body: String): String =
		pattern.find(body)?.groupValues?.get(1)?.let(::decodeXml).orEmpty().trim()

	private fun decodeXml(value: String): String = value
		.replace(Regex("""<!\[CDATA\[(.*?)]]>\s*""", RegexOption.DOT_MATCHES_ALL), "$1")
		.replace("&amp;", "&")
		.replace("&lt;", "<")
		.replace("&gt;", ">")
		.replace("&quot;", "\"")
		.replace("&apos;", "'")

	private fun parseProgramme(parser: XmlPullParser): ChannelFlowProgram? {
		val channelId = ChannelFlowIds.parse(parser.getAttributeValue(null, "channel"))
		val start = parseTime(parser.getAttributeValue(null, "start"))
		val end = parseTime(parser.getAttributeValue(null, "stop"))
		if (channelId == null || start == null || end == null || !end.isAfter(start)) {
			skipToEndTag(parser, "programme")
			return null
		}

		var title = ""
		var episodeTitle: String? = null
		var overview: String? = null
		var iconUrl: String? = null
		var officialRating: String? = null
		var productionYear: Int? = null
		val categories = mutableListOf<String>()

		var event = parser.next()
		while (!(event == XmlPullParser.END_TAG && parser.name.equals("programme", ignoreCase = true))) {
			if (event == XmlPullParser.END_DOCUMENT) break
			if (event == XmlPullParser.START_TAG) {
				when (parser.name.lowercase()) {
					"title" -> title = readText(parser)
					"sub-title" -> episodeTitle = readText(parser).ifBlank { null }
					"desc" -> overview = readText(parser).ifBlank { null }
					"category" -> readText(parser).ifBlank { null }?.let(categories::add)
					"icon" -> iconUrl = parser.getAttributeValue(null, "src")?.ifBlank { null }
					"date" -> productionYear = readText(parser).take(4).toIntOrNull()
					"value" -> if (officialRating == null) officialRating = readText(parser).ifBlank { null }
				}
			}
			event = parser.next()
		}

		if (title.isBlank()) title = "Program"
		val id = UUID.nameUUIDFromBytes("$channelId|$start|$title".toByteArray())
		return ChannelFlowProgram(
			id = id,
			channelId = channelId,
			title = title,
			episodeTitle = episodeTitle,
			overview = overview,
			start = start,
			end = end,
			iconUrl = iconUrl,
			categories = categories,
			officialRating = officialRating,
			productionYear = productionYear,
		)
	}

	private fun readText(parser: XmlPullParser): String {
		val depth = parser.depth
		val parts = StringBuilder()
		var event = parser.next()
		while (event != XmlPullParser.END_DOCUMENT) {
			when (event) {
				XmlPullParser.TEXT, XmlPullParser.CDSECT -> parts.append(parser.text.orEmpty())
				XmlPullParser.END_TAG -> if (parser.depth <= depth) {
					return parts.toString().trim()
				}
			}
			event = parser.next()
		}
		return parts.toString().trim()
	}

	private fun skipToEndTag(parser: XmlPullParser, tag: String) {
		var depth = 1
		var event = parser.eventType
		while (depth > 0 && event != XmlPullParser.END_DOCUMENT) {
			event = parser.next()
			when {
				event == XmlPullParser.START_TAG && parser.name.equals(tag, ignoreCase = true) -> depth++
				event == XmlPullParser.END_TAG && parser.name.equals(tag, ignoreCase = true) -> depth--
			}
		}
	}

	internal fun parseTime(raw: String?): LocalDateTime? {
		if (raw.isNullOrBlank()) return null
		val value = raw.trim().replace(Regex("\\s+"), " ")
		val digits = value.take(14)
		if (digits.length < 14 || digits.any { !it.isDigit() }) return null
		val local = runCatching { LocalDateTime.parse(digits, withoutOffset) }.getOrNull() ?: return null
		val offset = parseOffset(value.drop(14).trim()) ?: ZoneOffset.UTC
		return local.atOffset(offset).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
	}

	private fun parseOffset(raw: String): ZoneOffset? {
		if (raw.isBlank() || raw.equals("Z", ignoreCase = true) || raw.equals("UTC", ignoreCase = true)) {
			return ZoneOffset.UTC
		}
		val compact = raw.replace(":", "").replace(" ", "")
		if (compact.isEmpty()) return ZoneOffset.UTC
		val signChar = compact.first()
		if (signChar != '+' && signChar != '-') return ZoneOffset.UTC
		val digits = compact.drop(1).padEnd(4, '0')
		val hours = digits.take(2).toIntOrNull() ?: return ZoneOffset.UTC
		val minutes = digits.drop(2).take(2).toIntOrNull() ?: 0
		val sign = if (signChar == '-') -1 else 1
		return runCatching { ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes) }.getOrNull()
	}
}
