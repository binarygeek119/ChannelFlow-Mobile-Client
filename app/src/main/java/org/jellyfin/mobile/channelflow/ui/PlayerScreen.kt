package org.jellyfin.mobile.channelflow.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView
import org.jellyfin.mobile.R
import org.jellyfin.mobile.channelflow.ChannelFlowAppViewModel
import org.jellyfin.mobile.channelflow.ChannelFlowStream
import org.jellyfin.mobile.channelflow.ChannelFlowUrls
import java.util.UUID

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
	channelId: UUID,
	viewModel: ChannelFlowAppViewModel,
) {
	val context = LocalContext.current
	var title by remember { mutableStateOf("") }
	var error by remember { mutableStateOf<String?>(null) }
	var controlsVisible by remember { mutableStateOf(true) }

	BackHandler { viewModel.stopPlayback() }

	DisposableEffect(Unit) {
		val activity = context.findActivity()
		val previous = activity?.requestedOrientation
		activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
		onDispose {
			activity?.requestedOrientation = previous
				?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
		}
	}

	val player = remember {
		val extractors = DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(
			1800 * TsExtractor.TS_PACKET_SIZE
		)
		val loadControl = DefaultLoadControl.Builder()
			.setBufferDurationsMs(1_500, 15_000, 1_000, 1_500)
			.build()
		ExoPlayer.Builder(context)
			.setLoadControl(loadControl)
			.setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
			.build()
			.apply {
				playWhenReady = true
				repeatMode = Player.REPEAT_MODE_OFF
				videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
			}
	}

	DisposableEffect(player) {
		val listener = object : Player.Listener {
			override fun onPlayerError(playbackException: PlaybackException) {
				error = playbackException.localizedMessage ?: "Playback error"
			}
		}
		player.addListener(listener)
		onDispose {
			player.removeListener(listener)
			player.release()
		}
	}

	LaunchedEffect(channelId) {
		error = null
		val channel = viewModel.channel(channelId)
		title = listOfNotNull(channel?.number, channel?.name).joinToString("  ")
		val url = channel?.streamUrl
		if (url.isNullOrBlank()) {
			error = "No stream"
			return@LaunchedEffect
		}
		val apiKey = viewModel.servers.value.connection?.apiKey.orEmpty()
		val http = DefaultHttpDataSource.Factory()
			.setUserAgent(Util.getUserAgent(context, "ChannelFlow Mobile"))
			.setAllowCrossProtocolRedirects(true)
			.apply {
				if (apiKey.isNotBlank()) {
					setDefaultRequestProperties(mapOf("X-Api-Key" to apiKey))
				}
			}
		val playUrl = if (apiKey.isNotBlank()) ChannelFlowUrls.withApiKey(url, apiKey) else url
		val mediaItem = MediaItem.Builder()
			.setUri(playUrl)
			.setMimeType(
				when (ChannelFlowStream.mimeType(playUrl)) {
					ChannelFlowStream.MIME_HLS -> MimeTypes.APPLICATION_M3U8
					ChannelFlowStream.MIME_TS -> MimeTypes.VIDEO_MP2T
					else -> null
				}
			)
			.build()
		val source = if (ChannelFlowStream.isHls(playUrl)) {
			HlsMediaSource.Factory(http).createMediaSource(mediaItem)
		} else {
			DefaultMediaSourceFactory(http).createMediaSource(mediaItem)
		}
		player.setMediaSource(source)
		player.prepare()
		player.play()
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color.Black),
	) {
		AndroidView(
			factory = { ctx ->
				(LayoutInflater.from(ctx).inflate(R.layout.channelflow_player_view, null) as PlayerView).apply {
					keepScreenOn = true
					useController = true
					setShowNextButton(false)
					setShowPreviousButton(false)
					setShowFastForwardButton(false)
					setShowRewindButton(false)
					hidePlaybackButtons()
					setControllerVisibilityListener(
						PlayerView.ControllerVisibilityListener { visibility ->
							controlsVisible = visibility == View.VISIBLE
						},
					)
					layoutParams = ViewGroup.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT,
						ViewGroup.LayoutParams.MATCH_PARENT,
					)
					this.player = player
				}
			},
			update = { view ->
				view.player = player
				view.findViewById<View>(R.id.channel_up_button)?.setOnClickListener {
					viewModel.zap(channelId, higher = true)
					view.showController()
				}
				view.findViewById<View>(R.id.channel_down_button)?.setOnClickListener {
					viewModel.zap(channelId, higher = false)
					view.showController()
				}
			},
			modifier = Modifier.fillMaxSize(),
		)
		if (controlsVisible) {
			IconButton(
				onClick = { viewModel.stopPlayback() },
				modifier = Modifier
					.align(Alignment.TopStart)
					.padding(8.dp),
			) {
				Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.lbl_back), tint = Color.White)
			}
			Text(
				text = title,
				color = Color.White,
				style = MaterialTheme.typography.subtitle1,
				modifier = Modifier
					.align(Alignment.TopCenter)
					.padding(top = 16.dp),
			)
		}
		error?.let { message ->
			Text(
				text = message,
				color = Color.White,
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.padding(24.dp),
			)
		}
	}
}

private fun PlayerView.hidePlaybackButtons() {
	val ids = intArrayOf(
		R.id.previous_button,
		R.id.previous_chapter_button,
		R.id.play_pause_container,
		R.id.next_chapter_button,
		R.id.next_button,
		R.id.lock_screen_button,
		R.id.audio_streams_button,
		R.id.subtitles_button,
		R.id.speed_button,
		R.id.quality_button,
		R.id.decoder_button,
		R.id.info_button,
		R.id.fullscreen_switcher,
		androidx.media3.ui.R.id.exo_play_pause,
		androidx.media3.ui.R.id.exo_prev,
		androidx.media3.ui.R.id.exo_next,
		androidx.media3.ui.R.id.exo_rew,
		androidx.media3.ui.R.id.exo_ffwd,
	)
	for (id in ids) {
		findViewById<View>(id)?.visibility = View.GONE
	}
}

private fun Context.findActivity(): Activity? {
	var current: Context? = this
	while (current is ContextWrapper) {
		if (current is Activity) return current
		current = current.baseContext
	}
	return current as? Activity
}
