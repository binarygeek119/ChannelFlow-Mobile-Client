package org.jellyfin.mobile.channelflow.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jellyfin.mobile.R
import org.jellyfin.mobile.channelflow.ChannelFlowAppViewModel
import org.jellyfin.mobile.channelflow.ChannelGuideItem
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GuideScreen(viewModel: ChannelFlowAppViewModel) {
	val ui by viewModel.ui.collectAsState()
	val items = viewModel.filteredGuide()
	val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
	Column(
		modifier = Modifier
			.fillMaxSize()
			.systemBarsPadding()
			.background(MaterialTheme.colors.background),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 8.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Image(
				painter = painterResource(R.drawable.app_logo),
				contentDescription = stringResource(R.string.app_name),
				contentScale = ContentScale.Fit,
				alignment = Alignment.CenterStart,
				modifier = Modifier
					.height(40.dp)
					.weight(1f),
			)
			IconButton(onClick = { viewModel.refreshGuide(force = true) }) {
				Icon(
					Icons.Default.Refresh,
					contentDescription = stringResource(R.string.lbl_refresh_guide),
					tint = Color.White,
				)
			}
			IconButton(onClick = { viewModel.showSettings() }) {
				Icon(
					Icons.Default.Settings,
					contentDescription = stringResource(R.string.lbl_settings),
					tint = Color.White,
				)
			}
		}
		if (!landscape) {
			OutlinedTextField(
				value = ui.query,
				onValueChange = viewModel::setQuery,
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 4.dp),
				singleLine = true,
				placeholder = { Text(stringResource(R.string.lbl_search_channels)) },
				colors = TextFieldDefaults.outlinedTextFieldColors(
					focusedBorderColor = MaterialTheme.colors.primary,
					cursorColor = MaterialTheme.colors.primary,
				),
			)
		}
		when {
			ui.loadingGuide && items.isEmpty() -> {
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					CircularProgressIndicator(color = MaterialTheme.colors.primary)
				}
			}
			items.isEmpty() -> {
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					Text(
						stringResource(R.string.lbl_no_channels),
						color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
					)
				}
			}
			landscape -> {
				GuideGrid(
					items = items,
					onPlay = { viewModel.play(it.channel.id) },
					onProgram = { item, program ->
						viewModel.openProgram(program, channelLabel(item))
					},
				)
			}
			else -> {
				LazyColumn(modifier = Modifier.fillMaxSize()) {
					items(items, key = { it.channel.id }) { item ->
						NowPlayingRow(
							item = item,
							onPlay = { viewModel.play(item.channel.id) },
							onFavorite = { viewModel.toggleFavorite(item.channel.id) },
							onProgram = {
								val program = item.currentProgram ?: return@NowPlayingRow
								viewModel.openProgram(program, channelLabel(item))
							},
						)
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingRow(
	item: ChannelGuideItem,
	onPlay: () -> Unit,
	onFavorite: () -> Unit,
	onProgram: () -> Unit,
) {
	val now = item.currentProgram
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.combinedClickable(onClick = onPlay, onLongClick = onFavorite)
			.padding(horizontal = 16.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			modifier = Modifier
				.size(48.dp)
				.clip(RoundedCornerShape(8.dp))
				.background(MaterialTheme.colors.surface),
			contentAlignment = Alignment.Center,
		) {
			if (!item.channel.logoUrl.isNullOrBlank()) {
				AsyncImage(
					model = item.channel.logoUrl,
					contentDescription = item.channel.name,
					modifier = Modifier.fillMaxSize().padding(4.dp),
					contentScale = ContentScale.Fit,
				)
			} else {
				Icon(Icons.Default.Tv, contentDescription = null, tint = MaterialTheme.colors.primary)
			}
		}
		Spacer(Modifier.width(12.dp))
		Column(modifier = Modifier.weight(1f)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				if (!item.channel.number.isNullOrBlank()) {
					Text(
						text = item.channel.number,
						style = MaterialTheme.typography.caption,
						color = MaterialTheme.colors.primary,
						fontWeight = FontWeight.Bold,
					)
					Spacer(Modifier.width(8.dp))
				}
				Text(
					text = item.channel.name,
					style = MaterialTheme.typography.subtitle1,
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			if (now != null) {
				Text(
					text = now.title,
					style = MaterialTheme.typography.body2,
					color = MaterialTheme.colors.onBackground.copy(alpha = 0.8f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.clickable(onClick = onProgram),
				)
				val progress = programProgress(now.start, now.end)
				if (progress != null) {
					LinearProgressIndicator(
						progress = progress,
						modifier = Modifier
							.fillMaxWidth()
							.padding(top = 6.dp)
							.height(3.dp)
							.clip(RoundedCornerShape(2.dp)),
						color = MaterialTheme.colors.primary,
						backgroundColor = MaterialTheme.colors.onBackground.copy(alpha = 0.15f),
					)
				}
				Text(
					text = "${now.start.format(timeFormat)} – ${now.end.format(timeFormat)}",
					style = MaterialTheme.typography.caption,
					color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
				)
			} else {
				Text(
					text = stringResource(R.string.lbl_no_listing),
					style = MaterialTheme.typography.body2,
					color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
				)
			}
		}
		IconButton(onClick = onFavorite) {
			Icon(
				imageVector = if (item.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
				contentDescription = stringResource(R.string.lbl_favorite),
				tint = if (item.favorite) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
			)
		}
	}
}

private fun channelLabel(item: ChannelGuideItem): String =
	listOfNotNull(item.channel.number, item.channel.name).joinToString(" ")

private fun programProgress(start: LocalDateTime, end: LocalDateTime): Float? {
	val total = Duration.between(start, end).seconds
	if (total <= 0) return null
	val elapsed = Duration.between(start, LocalDateTime.now()).seconds
	return (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}
