package org.jellyfin.mobile.channelflow.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.R
import org.jellyfin.mobile.channelflow.ChannelFlowAppViewModel
import org.jellyfin.mobile.channelflow.ChannelFlowUpdateStatus
import org.jellyfin.mobile.channelflow.ChannelFlowVersion

@Composable
fun SettingsScreen(viewModel: ChannelFlowAppViewModel) {
	val servers by viewModel.servers.collectAsState()
	val update by viewModel.updateStatus.collectAsState()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()

	Column(
		modifier = Modifier
			.fillMaxSize()
			.systemBarsPadding(),
	) {
		TopAppBar(
			title = { Text(stringResource(R.string.lbl_settings), color = Color.White) },
			navigationIcon = {
				IconButton(onClick = { viewModel.showGuide() }) {
					Icon(
						Icons.AutoMirrored.Filled.ArrowBack,
						contentDescription = stringResource(R.string.lbl_back),
						tint = Color.White,
					)
				}
			},
			backgroundColor = MaterialTheme.colors.background,
			contentColor = Color.White,
			elevation = 0.dp,
		)
		Column(
			modifier = Modifier
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 20.dp, vertical = 8.dp),
		) {
			SectionTitle(stringResource(R.string.pref_connection), color = MaterialTheme.colors.primary)
			Text(
				text = servers.connection?.displayName() ?: stringResource(R.string.lbl_no_saved_servers),
				style = MaterialTheme.typography.body1,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colors.primary,
			)
			Text(
				text = stringResource(R.string.pref_connection_description),
				style = MaterialTheme.typography.caption,
				color = Color.White.copy(alpha = 0.6f),
			)
			Spacer(Modifier.height(12.dp))
			Button(
				onClick = { viewModel.showPair(force = true) },
				modifier = Modifier.fillMaxWidth(),
				colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary),
			) {
				Text(stringResource(R.string.lbl_add_server), color = Color.White)
			}

			if (servers.servers.size > 1) {
				Spacer(Modifier.height(16.dp))
				SectionTitle(stringResource(R.string.lbl_switch_server))
				servers.servers.forEach { server ->
					val active = server.id == servers.activeServerId
					Text(
						text = server.connection.displayName() + if (active) "  ✓" else "",
						modifier = Modifier
							.fillMaxWidth()
							.clickable { viewModel.switchServer(server.id) }
							.padding(vertical = 10.dp),
						color = if (active) MaterialTheme.colors.primary else Color.White,
					)
				}
			}

			if (servers.servers.isNotEmpty()) {
				Spacer(Modifier.height(8.dp))
				SectionTitle(stringResource(R.string.lbl_remove_server))
				servers.servers.forEach { server ->
					Text(
						text = server.connection.displayName(),
						modifier = Modifier
							.fillMaxWidth()
							.clickable { viewModel.removeServer(server.id) }
							.padding(vertical = 10.dp),
						color = Color.White,
					)
				}
			}

			Spacer(Modifier.height(16.dp))
			Divider(color = Color.White.copy(alpha = 0.12f))
			Spacer(Modifier.height(16.dp))
			SectionTitle(stringResource(R.string.lbl_updates))
			Text(
				text = when (val status = update) {
					is ChannelFlowUpdateStatus.Checking -> stringResource(R.string.lbl_checking_for_updates)
					is ChannelFlowUpdateStatus.UpToDate ->
						stringResource(R.string.lbl_app_up_to_date, ChannelFlowVersion.display(status.installed))
					is ChannelFlowUpdateStatus.Available ->
						stringResource(R.string.lbl_update_available, ChannelFlowVersion.display(status.latest))
					is ChannelFlowUpdateStatus.Downloading ->
						stringResource(R.string.lbl_downloading_update, status.progress)
					is ChannelFlowUpdateStatus.Installing -> stringResource(R.string.lbl_installing_update)
					is ChannelFlowUpdateStatus.Failed ->
						status.reason ?: stringResource(R.string.lbl_update_check_failed)
					ChannelFlowUpdateStatus.Idle -> stringResource(R.string.lbl_check_for_updates)
				},
				style = MaterialTheme.typography.body2,
				color = Color.White,
			)
			Spacer(Modifier.height(8.dp))
			Button(
				onClick = {
					val status = update
					if (status is ChannelFlowUpdateStatus.Available && !status.apkUrl.isNullOrBlank()) {
						if (viewModel.updater.needsInstallPermission()) {
							context.startActivity(viewModel.updater.installPermissionIntent())
						} else {
							viewModel.updater.startInstall(context)
						}
					} else {
						scope.launch { viewModel.updater.check(force = true) }
					}
				},
				modifier = Modifier.fillMaxWidth(),
				colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface),
			) {
				Text(
					if (update is ChannelFlowUpdateStatus.Available) {
						stringResource(R.string.lbl_install_update)
					} else {
						stringResource(R.string.lbl_check_for_updates)
					},
					color = Color.White,
				)
			}
			val available = update as? ChannelFlowUpdateStatus.Available
			if (available != null) {
				Text(
					text = stringResource(R.string.lbl_open_github_release),
					color = Color.White,
					modifier = Modifier
						.padding(top = 8.dp)
						.clickable {
							context.startActivity(Intent(Intent.ACTION_VIEW, available.pageUrl.toUri()))
						},
				)
			}

			Spacer(Modifier.height(24.dp))
			SectionTitle(stringResource(R.string.lbl_about))
			Image(
				painter = painterResource(R.drawable.app_logo),
				contentDescription = stringResource(R.string.app_name),
				contentScale = ContentScale.Fit,
				modifier = Modifier
					.fillMaxWidth()
					.height(80.dp)
					.padding(bottom = 8.dp),
			)
			Text(
				"ChannelFlow Mobile ${ChannelFlowVersion.display(BuildConfig.VERSION_NAME)}",
				color = Color.White,
			)
			Text("binarygeek119", color = Color.White.copy(alpha = 0.6f))
			Spacer(Modifier.height(32.dp))
		}
	}
}

@Composable
private fun SectionTitle(text: String, color: Color = Color.White) {
	Text(
		text = text.uppercase(),
		style = MaterialTheme.typography.overline,
		color = color,
		modifier = Modifier.padding(bottom = 6.dp),
	)
}
