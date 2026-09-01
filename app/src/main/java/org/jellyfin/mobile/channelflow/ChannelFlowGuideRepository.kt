package org.jellyfin.mobile.channelflow

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class ChannelGuideItem(
	val channel: ChannelFlowChannel,
	val currentProgram: ChannelFlowProgram?,
	val nextProgram: ChannelFlowProgram?,
	val favorite: Boolean,
	val programs: List<ChannelFlowProgram> = emptyList(),
)

class ChannelFlowGuideRepository(
	private val store: ChannelFlowConnectionStore,
	private val access: Lazy<ChannelFlowAccessGuard>,
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val mutex = Mutex()
	private val loadLock = Mutex()
	private var loadJob: Job? = null
	private val channelsReady = MutableStateFlow(false)
	private val programsReady = MutableStateFlow(false)
	private var channels: List<ChannelFlowChannel> = emptyList()
	private var programs: List<ChannelFlowProgram> = emptyList()
	private var loadedAt: Long = 0L
	private val http = OkHttpClient.Builder()
		.connectTimeout(30, TimeUnit.SECONDS)
		.readTimeout(3, TimeUnit.MINUTES)
		.writeTimeout(30, TimeUnit.SECONDS)
		.followRedirects(true)
		.followSslRedirects(true)
		.build()

	fun prefetchLatest() {
		if (!store.isConnected) return
		scope.launch {
			runCatching { ensureLoadStarted(force = false).join() }
				.onFailure { Timber.w(it, "Unable to refresh ChannelFlow guide") }
		}
	}

	suspend fun refresh(force: Boolean = false) {
		ensureLoadStarted(force).join()
	}

	fun findChannel(itemId: UUID): ChannelFlowChannel? {
		channels.firstOrNull { it.id == itemId }?.let { return it }
		val program = programs.firstOrNull { it.id == itemId } ?: return null
		return channels.firstOrNull { it.id == program.channelId }
	}

	fun getStreamUrl(itemId: UUID): String? {
		channels.firstOrNull { it.id == itemId }?.streamUrl?.let { return it }
		val program = programs.firstOrNull { it.id == itemId } ?: return null
		return channels.firstOrNull { it.id == program.channelId }?.streamUrl
	}

	suspend fun awaitStreamUrl(itemId: UUID): String? {
		awaitChannels()
		return mutex.withLock { getStreamUrl(itemId) }
	}

	fun getLogoUrl(itemId: UUID): String? {
		channels.firstOrNull { it.id == itemId }?.logoUrl?.let { return it }
		return programs.firstOrNull { it.id == itemId }?.iconUrl
	}

	suspend fun effectiveNow(): LocalDateTime {
		awaitPrograms()
		return mutex.withLock { ChannelFlowGuideClock.now() }
	}

	suspend fun getGuide(): List<ChannelGuideItem> {
		awaitChannels()
		awaitPrograms()
		val now = mutex.withLock { ChannelFlowGuideClock.now() }
		val windowStart = ChannelFlowGuideWindow.start(now)
		val windowEnd = ChannelFlowGuideWindow.end(windowStart)
		return mutex.withLock {
			val byChannel = programs.groupBy { it.channelId }
			channels
				.filter { it.hasPlayableStream() }
				.map { channel ->
					val listings = byChannel[channel.id].orEmpty().sortedBy { it.start }
					val current = listings.firstOrNull { it.start <= now && it.end > now }
					val next = listings.firstOrNull { it.start >= (current?.end ?: now) }
					ChannelGuideItem(
						channel = channel,
						currentProgram = current,
						nextProgram = next,
						favorite = store.isFavorite(channel.id),
						programs = listings.filter { it.start < windowEnd && it.end > windowStart },
					)
				}
				.sortedWith(
					compareByDescending<ChannelGuideItem> { it.favorite }
						.thenBy { ChannelNumber.parse(it.channel.number) ?: ChannelNumber(Int.MAX_VALUE, 0) }
						.thenBy { it.channel.name }
				)
		}
	}

	suspend fun getChannel(id: UUID): ChannelFlowChannel? {
		awaitChannels()
		return mutex.withLock {
			channels.firstOrNull { it.id == id && it.hasPlayableStream() }
		}
	}

	suspend fun getPrograms(
		channelIds: Collection<UUID>,
		startTime: LocalDateTime,
		endTime: LocalDateTime,
	): List<ChannelFlowProgram> {
		awaitPrograms()
		val ids = channelIds.toSet()
		if (ids.isEmpty()) return emptyList()
		return mutex.withLock {
			val matched = programs
				.filter { it.channelId in ids && it.start < endTime && it.end > startTime }
				.sortedBy { it.start }
			Timber.d("XMLTV matched ${matched.size} of ${programs.size} programmes for ${ids.size} channels window=$startTime..$endTime")
			matched
		}
	}

	suspend fun getProgram(id: UUID): ChannelFlowProgram? {
		awaitPrograms()
		return mutex.withLock {
			programs.firstOrNull { it.id == id }
		}
	}

	fun adjacentChannel(currentId: UUID, higher: Boolean): ChannelFlowChannel? =
		nextChannelWithContent(channels, programs, ChannelFlowGuideClock.now(), currentId, higher)

	fun clear() {
		loadJob?.cancel()
		loadJob = null
		channels = emptyList()
		programs = emptyList()
		loadedAt = 0L
		ChannelFlowGuideClock.updateCoverage(emptyList())
		channelsReady.value = false
		programsReady.value = false
	}

	private suspend fun awaitChannels() {
		if (store.connection == null) {
			clear()
			return
		}
		ensureLoadStarted()
		channelsReady.first { it }
	}

	private suspend fun awaitPrograms() {
		if (store.connection == null) {
			clear()
			return
		}
		ensureLoadStarted()
		programsReady.first { it }
	}

	private suspend fun ensureLoadStarted(force: Boolean = false): Job {
		val connection = store.connection ?: run {
			clear()
			return completedJob()
		}

		if (!force && isFresh()) {
			if (!channelsReady.value) channelsReady.value = true
			if (!programsReady.value) programsReady.value = true
			return loadJob?.takeIf { it.isActive } ?: completedJob()
		}

		return loadLock.withLock {
			if (!force && isFresh()) {
				return@withLock loadJob?.takeIf { it.isActive } ?: completedJob()
			}
			if (force) loadJob?.cancel()
			loadJob?.takeIf { it.isActive } ?: startLoad(connection)
		}
	}

	private fun startLoad(connection: ChannelFlowConnection): Job {
		channelsReady.value = false
		programsReady.value = false
		return scope.launch {
			load(connection)
		}.also { loadJob = it }
	}

	private suspend fun load(connection: ChannelFlowConnection) {
		supervisorScope {
			launch {
				try {
					val nextChannels = runCatching { M3uParser.parse(fetchText(connection, connection.m3uUrl)) }
						.onFailure { Timber.w(it, "Unable to load ChannelFlow M3U") }
						.getOrDefault(emptyList())
					mutex.withLock { channels = nextChannels }
					Timber.i("Loaded ${nextChannels.size} ChannelFlow channels")
				} finally {
					channelsReady.value = true
				}
			}
			launch {
				try {
					val nextPrograms = runCatching { XmltvParser.parse(fetchText(connection, connection.epgUrl)) }
						.onFailure { Timber.w(it, "Unable to load ChannelFlow XMLTV") }
						.getOrNull()
					if (nextPrograms != null) {
						mutex.withLock {
							programs = nextPrograms
							loadedAt = System.currentTimeMillis()
							ChannelFlowGuideClock.updateCoverage(nextPrograms)
						}
						val deviceNow = LocalDateTime.now()
						val guideNow = ChannelFlowGuideClock.now()
						if (guideNow != deviceNow) {
							Timber.w("Device clock $deviceNow is outside XMLTV coverage; guide using $guideNow")
						}
						Timber.i("Loaded ${nextPrograms.size} ChannelFlow XMLTV programmes")
					} else {
						Timber.w("ChannelFlow XMLTV fetch/parse failed; guide listings not updated")
					}
				} finally {
					programsReady.value = true
				}
			}
		}
	}

	private fun isFresh(): Boolean {
		val stale = System.currentTimeMillis() - loadedAt > CACHE_TTL_MS
		return !stale && channels.isNotEmpty() && programsReady.value && loadedAt > 0L
	}

	private suspend fun fetchText(connection: ChannelFlowConnection, url: String): String = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(withApiKey(url, connection.apiKey))
			.header("Accept", "application/xml, text/xml, audio/x-mpegurl, */*")
			.header("Accept-Encoding", "gzip")
			.apply {
				if (connection.apiKey.isNotBlank()) header("X-Api-Key", connection.apiKey)
			}
			.get()
			.build()
		http.newCall(request).execute().use { response ->
			if (response.code == 401 || response.code == 403) {
				access.value.forgetUnauthorized(connection)
				error("HTTP ${response.code} unauthorized for ${redact(url)}")
			}
			if (!response.isSuccessful) error("HTTP ${response.code} for ${redact(url)}")
			val bytes = response.body?.bytes() ?: ByteArray(0)
			decodeBody(bytes)
		}
	}

	private fun decodeBody(bytes: ByteArray): String {
		if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
			return GZIPInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
		}
		return bytes.toString(Charsets.UTF_8)
	}

	companion object {
		private const val CACHE_TTL_MS = 2 * 60 * 1000L

		private fun completedJob(): Job = Job().apply { complete() }

		private fun withApiKey(url: String, apiKey: String): String {
			if (apiKey.isBlank() || url.contains("apiKey=", ignoreCase = true)) return url
			val uri = Uri.parse(url).buildUpon().appendQueryParameter("apiKey", apiKey).build()
			return uri.toString()
		}

		private fun redact(url: String): String = url.replace(Regex("apiKey=[^&]*", RegexOption.IGNORE_CASE), "apiKey=***")
	}
}

internal fun nextChannelWithContent(
	channels: List<ChannelFlowChannel>,
	programs: List<ChannelFlowProgram>,
	now: LocalDateTime,
	currentId: UUID,
	higher: Boolean,
): ChannelFlowChannel? {
	val sorted = channels
		.filter { it.hasPlayableStream() }
		.sortedWith(
			compareBy<ChannelFlowChannel> { ChannelNumber.parse(it.number) ?: ChannelNumber(Int.MAX_VALUE, 0) }
				.thenBy { it.name },
		)
	if (sorted.isEmpty()) return null
	val index = sorted.indexOfFirst { it.id == currentId }.let { found -> if (found < 0) 0 else found }
	val liveIds = programs
		.asSequence()
		.filter { it.start <= now && it.end > now }
		.map { it.channelId }
		.toHashSet()
	val step = if (higher) 1 else -1
	var neighbor: ChannelFlowChannel? = null
	for (offset in 1 until sorted.size) {
		val candidate = sorted[(index + step * offset).mod(sorted.size)]
		if (candidate.id == currentId) break
		if (neighbor == null) neighbor = candidate
		if (candidate.id in liveIds) return candidate
	}
	return neighbor
}
