package org.jellyfin.mobile.channelflow

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
) {
	private val app = context.applicationContext
	private val main = Handler(Looper.getMainLooper())
	private val libVlc: LibVLC
	private val player: MediaPlayer
	private var layout: VLCVideoLayout? = null
	private var attached = false
	private var lastUrl: String? = null
	private var lastApiKey: String? = null
	private var retriedSoft = false
	private var pending: PendingPlay? = null

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
		pending = PendingPlay(url, name, channelId, number, logoUrl, apiKey)
		if (attached) startPending()
		else layout?.post { startPending() }
	}

	fun release() {
		pending = null
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
		if (layout !== videoLayout) return
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
		if (!attached) return
		pending = null
		lastUrl = request.url
		lastApiKey = request.apiKey
		retriedSoft = false
		runCatching {
			val playlist = ChannelFlowVlcPlaylist.write(
				file = File(File(app.cacheDir, "vlc"), "channel.m3u"),
				streamUrl = request.url,
				name = request.name ?: "ChannelFlow",
				channelId = request.channelId,
				number = request.number,
				logoUrl = request.logoUrl,
				apiKey = request.apiKey,
			)
			val playUrl = ChannelFlowVlcPlaylist.streamUrlFrom(playlist.readText())
				?: ChannelFlowVlcPlaylist.withApiKey(request.url, request.apiKey)
			val media = mediaFromPlaylist(playlist) ?: mediaFromUrl(playUrl, preferHardware = true)
			player.media = media
			media.release()
			player.play()
			Timber.i("VLC playing live url=%s", ChannelFlowStream.redact(playUrl))
		}.onFailure { error ->
			Timber.e(error, "VLC failed to start stream")
			onError(error.message ?: "Playback error")
		}
	}

	private fun mediaFromPlaylist(file: File): Media? {
		val playlist = Media(libVlc, file.absolutePath)
		var stream: Media? = null
		try {
			if (!playlist.parse(IMedia.Parse.ParseLocal)) return null
			val items = playlist.subItems()
			try {
				if (items.count <= 0) return null
				stream = items.getMediaAt(0) as? Media ?: return null
				applyStreamOptions(stream, preferHardware = true)
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
		when (event.type) {
			MediaPlayer.Event.Opening -> Timber.i("VLC opening stream")
			MediaPlayer.Event.Playing -> Timber.i("VLC playing")
			MediaPlayer.Event.Vout -> Timber.i("VLC video output ready")
			MediaPlayer.Event.EndReached -> {
				Timber.w("VLC live stream ended; restarting")
				restart(preferHardware = true)
			}
			MediaPlayer.Event.EncounteredError -> {
				val url = lastUrl
				if (!retriedSoft && url != null) {
					retriedSoft = true
					Timber.w("VLC playback error; retrying without hardware decode")
					restart(preferHardware = false)
				} else {
					Timber.e("VLC playback error")
					onError("Playback error")
				}
			}
		}
	}

	private fun restart(preferHardware: Boolean) {
		val url = lastUrl ?: return
		val playUrl = ChannelFlowVlcPlaylist.withApiKey(url, lastApiKey)
		runCatching {
			val media = mediaFromUrl(playUrl, preferHardware)
			player.media = media
			media.release()
			player.play()
		}.onFailure { error ->
			Timber.e(error, "VLC restart failed")
			onError(error.message ?: "Playback error")
		}
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
