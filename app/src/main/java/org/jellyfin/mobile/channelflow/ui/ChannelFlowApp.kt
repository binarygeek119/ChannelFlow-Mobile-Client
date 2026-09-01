package org.jellyfin.mobile.channelflow.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.channelflow.ChannelFlowAppViewModel
import org.jellyfin.mobile.channelflow.ChannelFlowScreen
import org.koin.androidx.compose.koinViewModel

@Composable
fun ChannelFlowApp(
	viewModel: ChannelFlowAppViewModel = koinViewModel(),
) {
	val ui by viewModel.ui.collectAsState()
	val reminder by viewModel.pendingReminder.collectAsState()
	val activity = LocalContext.current as? Activity

	LaunchedEffect(activity?.intent) {
		if (activity?.intent?.getBooleanExtra(MainActivity.EXTRA_RECONNECT, false) == true) {
			viewModel.showPair(force = true)
			activity.intent.removeExtra(MainActivity.EXTRA_RECONNECT)
		}
	}

	when (val screen = ui.screen) {
		ChannelFlowScreen.Pair -> PairScreen(viewModel)
		ChannelFlowScreen.Guide -> GuideScreen(viewModel)
		is ChannelFlowScreen.Player -> PlayerScreen(channelId = screen.channelId, viewModel = viewModel)
		ChannelFlowScreen.Settings -> {
			BackHandler { viewModel.showGuide() }
			SettingsScreen(viewModel)
		}
	}

	val program = ui.selectedProgram
	if (program != null && ui.screen !is ChannelFlowScreen.Player) {
		ProgramDetailDialog(
			program = program,
			channelLabel = ui.selectedChannelLabel,
			reminderSet = viewModel.reminders.isSet(program.id),
			onPlay = { viewModel.play(program.channelId) },
			onToggleReminder = { viewModel.toggleReminder() },
			onDismiss = { viewModel.dismissProgram() },
		)
	}

	val due = reminder
	if (due != null) {
		AlertDialog(
			onDismissRequest = { viewModel.acknowledgeReminder(play = false) },
			title = { Text(due.title) },
			text = {
				Text(
					listOfNotNull(due.channelLabel, due.episodeTitle)
						.joinToString(" · ")
						.ifBlank { stringResource(R.string.lbl_on_now) },
				)
			},
			confirmButton = {
				TextButton(onClick = { viewModel.acknowledgeReminder(play = true) }) {
					Text(stringResource(R.string.lbl_watch_now), color = MaterialTheme.colors.primary)
				}
			},
			dismissButton = {
				TextButton(onClick = { viewModel.acknowledgeReminder(play = false) }) {
					Text(stringResource(R.string.lbl_keep_watching))
				}
			},
		)
	}
}
