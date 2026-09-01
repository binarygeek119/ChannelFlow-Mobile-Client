package org.jellyfin.mobile.channelflow

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import timber.log.Timber
import java.io.File
import java.util.UUID

class ChannelFlowVlcEngine(
	context: Context,
	private val onError: (String) -> Unit,
	private val onPlaying: () -> Unit = {},
) {
	private val app = context.applicationContext
	private val main = Handler(Looper.getMainLooper())
	private val libVlc: LibVLC
	private val player: MediaPlayer
	private var layout: VLCVideoLayout? = null
	private var attached = false
	private var lastUrl: String? = null
	private var lastApiKey: String? = null
	private var lastName: String? = null
	private var lastChannelId: UUID? = null
	private var lastNumber: String? = null
	private var lastLogoUrl: String? = null
	private var pending: PendingPlay? = null
	private var released = false
	private var reconnectPosted = false
	private var ignoreStopped = false
	private var awaitingFirstFrame = false
	private var consecutiveFailures = 0
	private var lastProgressAt = 0L
	private var lastPlayerTime = -1L
	private var sawTimeAdvance = false
	private var bufferingPercent = 100f

	private val watchdog = object : Runnable {
		override fun run() {
			if (released) return
			checkStall()
			main.postDelayed(this, ChannelFlowLiveReconnect.WATCHDOG_MS)
		}
	}

	private val reconnect = Runnable {
		reconnectPosted = false
		if (released || lastUrl == null) return@Runnable
		startStream(preferHardware = ChannelFlowLiveReconnect.preferHardware(consecutiveFailures))
	}

	init {
		libVlc = LibVLC(
			context,
			arrayListOf(
				"--network-caching=${ChannelFlowVlcPlaylist.START_CACHE_MS}",
				"--live-caching=${ChannelFlowVlcPlaylist.START_CACHE_MS}",
				"--stream-filter=prefetch",
				"--prefetch-buffer-size=${ChannelFlowVlcPlaylist.PREFETCH_BUFFER_KIB}",
				"--prefetch-read-size=${ChannelFlowVlcPlaylist.PREFETCH_READ_BYTES}",
				"--http-reconnect",
				"--http-user-agent=${ChannelFlowVlcPlaylist.USER_AGENT}",
				"--aout=opensles",
				"--audio-time-stretch",
			),
		)
		player = MediaPlayer(libVlc)
		player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
		player.setEventListener { event -> main.post { onEvent(event) } }
	}

	fun attach(videoLayout: VLCVideoLayout) {
		if (layout !== videoLayout) {
			if (attached) {
				runCatching { player.detachViews() }
				attached = false
			}
			layout = videoLayout
		}
		videoLayout.keepScreenOn = true
		videoLayout.visibility = android.view.View.VISIBLE
		videoLayout.post { bindViews(videoLayout) }
	}

	fun play(
		url: String,
		name: String?,
		channelId: UUID?,
		number: String?,
		logoUrl: String?,
		apiKey: String?,
	) {
		cancelReconnect()
		consecutiveFailures = 0
		pending = PendingPlay(url, name, channelId, number, logoUrl, apiKey)
		if (attached) startPending()
		else layout?.post { startPending() }
	}

	fun release() {
		released = true
		pending = null
		cancelReconnect()
		main.removeCallbacks(watchdog)
		runCatching { player.stop() }
		if (attached) {
			runCatching { player.detachViews() }
			attached = false
		}
		layout = null
		runCatching { player.release() }
		runCatching { libVlc.release() }
	}

	private fun bindViews(videoLayout: VLCVideoLayout) {
		if (released || layout !== videoLayout) return
		if (!attached) {
			runCatching {
				// TextureView composites inside Compose; SurfaceView often stays black.
				player.attachViews(videoLayout, null, false, true)
				attached = true
				Timber.i("VLC video surface attached")
			}.onFailure { error ->
				Timber.e(error, "VLC attachViews failed")
				onError(error.message ?: "Playback error")
				return
			}
		}
		startPending()
	}

	private fun startPending() {
		val request = pending ?: return
		if (!attached || released) return
		pending = null
		lastUrl = request.url
		lastApiKey = request.apiKey
		lastName = request.name
		lastChannelId = request.channelId
		lastNumber = request.number
		lastLogoUrl = request.logoUrl
		ensureWatchdog()
		startStream(preferHardware = true, usePlaylist = true)
	}

	private fun startStream(preferHardware: Boolean, usePlaylist: Boolean = false) {
		val url = lastUrl ?: return
		if (!attached || released) return
		awaitingFirstFrame = true
		lastProgressAt = SystemClock.elapsedRealtime()
		lastPlayerTime = -1L
		sawTimeAdvance = false
		bufferingPercent = 100f
		runCatching {
			ignoreStopped = true
			runCatching { player.stop() }
			val playUrl = ChannelFlowLiveReconnect.withCacheBust(
				ChannelFlowVlcPlaylist.withApiKey(url, lastApiKey),
				System.currentTimeMillis(),
			)
			val media = if (usePlaylist) {
				val playlist = ChannelFlowVlcPlaylist.write(
					file = File(File(app.cacheDir, "vlc"), "channel.m3u"),
					streamUrl = url,
					name = lastName ?: "ChannelFlow",
					channelId = lastChannelId,
					number = lastNumber,
					logoUrl = lastLogoUrl,
					apiKey = lastApiKey,
				)
				mediaFromPlaylist(playlist, preferHardware) ?: mediaFromUrl(playUrl, preferHardware)
			} else {
				mediaFromUrl(playUrl, preferHardware)
			}
			player.media = media
			media.release()
			player.play()
			Timber.i("VLC playing live url=%s", ChannelFlowStream.redact(playUrl))
		}.onFailure { error ->
			Timber.e(error, "VLC failed to start stream")
			scheduleReconnect("start-failed")
		}
	}

	private fun mediaFromPlaylist(file: File, preferHardware: Boolean): Media? {
		val playlist = Media(libVlc, file.absolutePath)
		var stream: Media? = null
		try {
			if (!playlist.parse(IMedia.Parse.ParseLocal)) return null
			val items = playlist.subItems()
			try {
				if (items.count <= 0) return null
				stream = items.getMediaAt(0) as? Media ?: return null
				applyStreamOptions(stream, preferHardware)
			} finally {
				items.release()
			}
		} catch (error: Throwable) {
			Timber.w(error, "VLC could not parse M3U playlist")
			stream = null
		} finally {
			playlist.release()
		}
		return stream
	}

	private fun mediaFromUrl(url: String, preferHardware: Boolean): Media {
		val media = Media(libVlc, Uri.parse(url))
		applyStreamOptions(media, preferHardware)
		return media
	}

	private fun applyStreamOptions(media: Media, preferHardware: Boolean) {
		media.setHWDecoderEnabled(preferHardware, true)
		media.addOption(":network-caching=${ChannelFlowVlcPlaylist.START_CACHE_MS}")
		media.addOption(":http-reconnect")
		media.addOption(":http-user-agent=${ChannelFlowVlcPlaylist.USER_AGENT}")
		val apiKey = lastApiKey
		if (!apiKey.isNullOrBlank()) media.addOption(":http-header=X-Api-Key: $apiKey")
		media.addOption(":live-caching=${ChannelFlowVlcPlaylist.START_CACHE_MS}")
		media.addOption(":stream-filter=prefetch")
		media.addOption(":prefetch-buffer-size=${ChannelFlowVlcPlaylist.PREFETCH_BUFFER_KIB}")
		media.addOption(":prefetch-read-size=${ChannelFlowVlcPlaylist.PREFETCH_READ_BYTES}")
		media.addOption(":clock-jitter=0")
		media.addOption(":clock-synchro=0")
		val url = media.uri?.toString() ?: lastUrl.orEmpty()
		if (ChannelFlowStream.isMpegTs(url) && !ChannelFlowStream.isHls(url)) {
			media.addOption(":demux=ts")
		}
	}

	private fun onEvent(event: MediaPlayer.Event) {
		if (released) return
		when (event.type) {
			MediaPlayer.Event.Opening -> {
				ignoreStopped = false
				markProgress()
				Timber.i("VLC opening stream")
			}
			MediaPlayer.Event.Buffering -> {
				bufferingPercent = event.buffering
			}
			MediaPlayer.Event.Playing -> {
				ignoreStopped = false
				awaitingFirstFrame = false
				consecutiveFailures = 0
				markProgress()
				onPlaying()
				Timber.i("VLC playing")
			}
			MediaPlayer.Event.Vout -> {
				awaitingFirstFrame = false
				markProgress()
				Timber.i("VLC video output ready")
			}
			MediaPlayer.Event.TimeChanged -> {
				val time = player.time
				if (time != lastPlayerTime) {
					if (lastPlayerTime >= 0) sawTimeAdvance = true
					lastPlayerTime = time
					markProgress()
				}
			}
			MediaPlayer.Event.EndReached -> {
				Timber.w("VLC live stream ended; reconnecting")
				scheduleReconnect("ended")
			}
			MediaPlayer.Event.EncounteredError -> {
				Timber.w("VLC playback error; reconnecting")
				scheduleReconnect("error")
			}
			MediaPlayer.Event.Stopped -> {
				if (!ignoreStopped && lastUrl != null && !reconnectPosted) {
					Timber.w("VLC live stream stopped; reconnecting")
					scheduleReconnect("stopped")
				}
			}
		}
	}

	private fun markProgress() {
		lastProgressAt = SystemClock.elapsedRealtime()
	}

	private fun checkStall() {
		if (released || reconnectPosted || lastUrl == null || !attached) return
		if (!ChannelFlowLiveReconnect.isStalled(
				nowMs = SystemClock.elapsedRealtime(),
				lastProgressMs = lastProgressAt,
				awaitingFirstFrame = awaitingFirstFrame,
				sawTimeAdvance = sawTimeAdvance,
				bufferingPercent = bufferingPercent,
			)
		) {
			return
		}
		val reason = if (awaitingFirstFrame) "open-timeout" else "stall"
		Timber.w("VLC live %s; reconnecting", reason)
		scheduleReconnect(reason)
	}

	private fun scheduleReconnect(reason: String) {
		if (released || lastUrl == null || reconnectPosted) return
		reconnectPosted = true
		ignoreStopped = true
		val delay = ChannelFlowLiveReconnect.delayMs(consecutiveFailures)
		consecutiveFailures += 1
		onError("Reconnecting…")
		Timber.i("VLC live reconnect in %sms (%s, attempt %s)", delay, reason, consecutiveFailures)
		main.removeCallbacks(reconnect)
		main.postDelayed(reconnect, delay)
		runCatching { player.stop() }
	}

	private fun cancelReconnect() {
		reconnectPosted = false
		ignoreStopped = false
		main.removeCallbacks(reconnect)
	}

	private fun ensureWatchdog() {
		if (released) return
		main.removeCallbacks(watchdog)
		main.postDelayed(watchdog, ChannelFlowLiveReconnect.WATCHDOG_MS)
	}

	private data class PendingPlay(
		val url: String,
		val name: String?,
		val channelId: UUID?,
		val number: String?,
		val logoUrl: String?,
		val apiKey: String?,
	)
}
