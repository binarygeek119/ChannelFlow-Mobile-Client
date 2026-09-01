package org.jellyfin.mobile.channelflow.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R
import org.jellyfin.mobile.channelflow.ChannelFlowProgram
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")

@Composable
fun ProgramDetailDialog(
	program: ChannelFlowProgram,
	channelLabel: String?,
	reminderSet: Boolean,
	onPlay: () -> Unit,
	onToggleReminder: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(program.title) },
		text = {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				if (!channelLabel.isNullOrBlank()) {
					Text(channelLabel, color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
					Spacer(Modifier.height(4.dp))
				}
				program.episodeTitle?.let {
					Text(it, style = MaterialTheme.typography.subtitle2)
					Spacer(Modifier.height(4.dp))
				}
				Text(
					"${program.start.format(timeFormat)} – ${program.end.format(timeFormat)}",
					style = MaterialTheme.typography.caption,
					color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
				)
				val meta = listOfNotNull(
					program.officialRating,
					program.productionYear?.toString(),
					program.categories.takeIf { it.isNotEmpty() }?.joinToString(", "),
				).joinToString("  ·  ")
				if (meta.isNotBlank()) {
					Spacer(Modifier.height(4.dp))
					Text(meta, style = MaterialTheme.typography.caption)
				}
				program.overview?.let {
					Spacer(Modifier.height(12.dp))
					Text(it, style = MaterialTheme.typography.body2)
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.lbl_watch_now), color = MaterialTheme.colors.primary)
			}
		},
		dismissButton = {
			TextButton(onClick = onToggleReminder) {
				Text(
					if (reminderSet) stringResource(R.string.lbl_cancel_reminder) else stringResource(R.string.lbl_remind),
					color = MaterialTheme.colors.onBackground,
				)
			}
		},
	)
}
