package org.jellyfin.mobile.channelflow.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.jellyfin.mobile.R
import org.jellyfin.mobile.channelflow.ChannelFlowAppViewModel
import org.jellyfin.mobile.channelflow.ChannelFlowVlcEngine
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.UUID

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

	val activity = context.findActivity()
	val engine = remember(activity) {
		ChannelFlowVlcEngine(activity ?: context) { message -> error = message }
	}
	DisposableEffect(engine) {
		onDispose { engine.release() }
	}

	LaunchedEffect(channelId) {
		error = null
		controlsVisible = true
		val channel = viewModel.channel(channelId)
		title = listOfNotNull(channel?.number, channel?.name).joinToString("  ")
		val url = channel?.streamUrl
		if (url.isNullOrBlank()) {
			error = "No stream"
			return@LaunchedEffect
		}
		val apiKey = viewModel.servers.value.connection?.apiKey.orEmpty()
		runCatching {
			engine.play(
				url = url,
				name = channel.name,
				channelId = channel.id,
				number = channel.number,
				logoUrl = channel.logoUrl,
				apiKey = apiKey.ifBlank { null },
			)
		}.onFailure { failed ->
			error = failed.message ?: "Playback error"
		}
	}

	LaunchedEffect(controlsVisible, channelId) {
		if (!controlsVisible) return@LaunchedEffect
		delay(4_000)
		controlsVisible = false
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color.Black),
	) {
		AndroidView(
			factory = { ctx ->
				VLCVideoLayout(ctx).apply {
					keepScreenOn = true
					layoutParams = ViewGroup.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT,
						ViewGroup.LayoutParams.MATCH_PARENT,
					)
					engine.attach(this)
				}
			},
			update = { layout -> engine.attach(layout) },
			modifier = Modifier.fillMaxSize(),
		)
		Box(
			modifier = Modifier
				.fillMaxSize()
				.clickable { controlsVisible = !controlsVisible },
		)
		if (controlsVisible) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(Color(0x60000000))
					.clickable { controlsVisible = false },
			)
			IconButton(
				onClick = { viewModel.stopPlayback() },
				modifier = Modifier
					.align(Alignment.TopStart)
					.padding(8.dp),
			) {
				Icon(
					Icons.AutoMirrored.Filled.ArrowBack,
					contentDescription = stringResource(R.string.lbl_back),
					tint = Color.White,
				)
			}
			Text(
				text = title,
				color = Color.White,
				style = MaterialTheme.typography.subtitle1,
				modifier = Modifier
					.align(Alignment.TopCenter)
					.padding(top = 16.dp),
			)
			Column(
				modifier = Modifier
					.align(Alignment.CenterEnd)
					.padding(end = 32.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				IconButton(onClick = { viewModel.zap(channelId, higher = true) }) {
					Icon(
						painter = painterResource(R.drawable.ic_channel_up),
						contentDescription = stringResource(R.string.lbl_channel_up),
						tint = Color.White,
					)
				}
				IconButton(
					onClick = { viewModel.zap(channelId, higher = false) },
					modifier = Modifier.padding(top = 12.dp),
				) {
					Icon(
						painter = painterResource(R.drawable.ic_channel_down),
						contentDescription = stringResource(R.string.lbl_channel_down),
						tint = Color.White,
					)
				}
			}
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

private fun Context.findActivity(): Activity? {
	var current: Context? = this
	while (current is ContextWrapper) {
		if (current is Activity) return current
		current = current.baseContext
	}
	return current as? Activity
}
