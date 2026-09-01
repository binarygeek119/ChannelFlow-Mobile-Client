package org.jellyfin.mobile.channelflow.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.mobile.R
import org.jellyfin.mobile.channelflow.ChannelFlowAppViewModel

@Composable
fun PairScreen(viewModel: ChannelFlowAppViewModel) {
	val ui by viewModel.ui.collectAsState()
	Column(
		modifier = Modifier
			.fillMaxSize()
			.systemBarsPadding()
			.padding(horizontal = 32.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		Image(
			painter = painterResource(R.drawable.app_logo),
			contentDescription = stringResource(R.string.app_name),
			contentScale = ContentScale.Fit,
			modifier = Modifier
				.fillMaxWidth()
				.height(120.dp),
		)
		Spacer(Modifier.height(32.dp))
		Text(
			text = stringResource(R.string.lbl_your_quick_pin),
			style = MaterialTheme.typography.h6,
			color = MaterialTheme.colors.onBackground,
		)
		Spacer(Modifier.height(12.dp))
		Text(
			text = ui.pin.ifBlank { "••••-••••" },
			fontFamily = FontFamily.Monospace,
			fontWeight = FontWeight.Bold,
			fontSize = 36.sp,
			color = MaterialTheme.colors.primary,
			letterSpacing = 2.sp,
		)
		Spacer(Modifier.height(16.dp))
		Text(
			text = stringResource(R.string.lbl_quick_pin_help),
			style = MaterialTheme.typography.body2,
			color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
			textAlign = TextAlign.Center,
		)
		Spacer(Modifier.height(24.dp))
		when {
			ui.pairingError != null -> {
				Text(
					text = if (ui.pairingError == "expired") {
						stringResource(R.string.msg_quick_pin_expired)
					} else {
						stringResource(R.string.msg_pin_server_unreachable)
					},
					color = MaterialTheme.colors.error,
					textAlign = TextAlign.Center,
				)
				Spacer(Modifier.height(16.dp))
				Button(
					onClick = { viewModel.startPairing() },
					modifier = Modifier.fillMaxWidth(),
					colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary),
				) {
					Text(stringResource(R.string.lbl_retry), color = MaterialTheme.colors.onPrimary)
				}
			}
			ui.pairing -> {
				CircularProgressIndicator(color = MaterialTheme.colors.primary)
				Spacer(Modifier.height(12.dp))
				Text(
					text = if (ui.pin.isBlank()) {
						stringResource(R.string.lbl_quick_pin_waiting)
					} else {
						stringResource(R.string.lbl_quick_pin_waiting_server)
					},
					style = MaterialTheme.typography.body2,
					color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
				)
			}
		}
	}
}
