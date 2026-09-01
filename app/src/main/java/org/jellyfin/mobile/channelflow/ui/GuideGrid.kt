package org.jellyfin.mobile.channelflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.jellyfin.mobile.channelflow.ChannelFlowGuideClock
import org.jellyfin.mobile.channelflow.ChannelFlowGuideWindow
import org.jellyfin.mobile.channelflow.ChannelFlowProgram
import org.jellyfin.mobile.channelflow.ChannelGuideItem
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val channelColWidth = 148.dp
private val rowHeight = 56.dp
private val headerHeight = 32.dp
private val pxPerMinute = 3.dp
private val blockFill = Color(0xFF242424)
private val nowFill = Color(0xFF3A121C)
private val drawerFill = Color(0xFF202020)
private val accent = Color(0xFFE11D48)
private val stripeDefault = Color(0xFF525252)
private val stripeMovie = Color(0xFFC084FC)
private val stripeNews = Color(0xFFFBBF24)
private val stripeWeather = Color(0xFF22D3EE)
private val stripeMusic = Color(0xFF34D399)
private val stripeShow = Color(0xFF60A5FA)
private val headerTime = DateTimeFormatter.ofPattern("h:mm a")

@Composable
fun GuideGrid(
	items: List<ChannelGuideItem>,
	onPlay: (ChannelGuideItem) -> Unit,
	onProgram: (ChannelGuideItem, ChannelFlowProgram) -> Unit,
) {
	val now = ChannelFlowGuideClock.now()
	val windowStart = ChannelFlowGuideWindow.start(now)
	val windowMinutes = ChannelFlowGuideWindow.minutes().toInt()
	val timelineWidth = pxPerMinute * windowMinutes
	val hScroll = rememberScrollState()
	val nowOffset = pxPerMinute * Duration.between(windowStart, now).toMinutes().toInt().coerceIn(0, windowMinutes)
	val slots = (windowMinutes / ChannelFlowGuideWindow.SLOT_MINUTES.toInt())

	Column(modifier = Modifier.fillMaxSize()) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(headerHeight)
				.background(drawerFill),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = now.format(headerTime),
				color = accent,
				fontSize = 12.sp,
				fontWeight = FontWeight.Bold,
				modifier = Modifier
					.width(channelColWidth)
					.padding(horizontal = 8.dp),
			)
			Box(modifier = Modifier.weight(1f)) {
				Row(modifier = Modifier.horizontalScroll(hScroll)) {
					Box(modifier = Modifier.width(timelineWidth).height(headerHeight)) {
						repeat(slots) { index ->
							val tick = windowStart.plusMinutes(index * ChannelFlowGuideWindow.SLOT_MINUTES)
							Text(
								text = tick.format(headerTime),
								color = Color(0xFF9B9B9B),
								fontSize = 11.sp,
								modifier = Modifier
									.offset(x = pxPerMinute * (index * ChannelFlowGuideWindow.SLOT_MINUTES.toInt()))
									.padding(start = 6.dp, top = 8.dp),
							)
						}
						NowLine(offset = nowOffset, height = headerHeight)
					}
				}
			}
		}
		LazyColumn(modifier = Modifier.fillMaxSize()) {
			items(items, key = { it.channel.id }) { item ->
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.height(rowHeight),
				) {
					ChannelCell(
						item = item,
						modifier = Modifier
							.width(channelColWidth)
							.fillMaxHeight()
							.clickable { onPlay(item) },
					)
					Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
						Box(
							modifier = Modifier
								.fillMaxHeight()
								.horizontalScroll(hScroll),
						) {
							Box(modifier = Modifier.width(timelineWidth).height(rowHeight)) {
								item.programs.forEach { program ->
									ProgramBlock(
										program = program,
										windowStart = windowStart,
										windowMinutes = windowMinutes,
										now = now,
										onClick = {
											if (!now.isBefore(program.start) && now.isBefore(program.end)) {
												onPlay(item)
											} else {
												onProgram(item, program)
											}
										},
									)
								}
								NowLine(offset = nowOffset, height = rowHeight)
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun ChannelCell(
	item: ChannelGuideItem,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.background(drawerFill)
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			modifier = Modifier
				.size(36.dp)
				.clip(RoundedCornerShape(6.dp))
				.background(Color(0xFF181818)),
			contentAlignment = Alignment.Center,
		) {
			if (!item.channel.logoUrl.isNullOrBlank()) {
				AsyncImage(
					model = item.channel.logoUrl,
					contentDescription = item.channel.name,
					modifier = Modifier.fillMaxSize().padding(3.dp),
					contentScale = ContentScale.Fit,
				)
			} else {
				Icon(Icons.Default.Tv, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
			}
		}
		Spacer(Modifier.width(8.dp))
		Column(modifier = Modifier.weight(1f)) {
			if (!item.channel.number.isNullOrBlank()) {
				Text(
					text = item.channel.number.orEmpty(),
					color = accent,
					fontSize = 10.sp,
					fontWeight = FontWeight.Bold,
					maxLines = 1,
				)
			}
			Text(
				text = item.channel.name,
				color = Color(0xFFEEEEEE),
				fontSize = 12.sp,
				fontWeight = FontWeight.SemiBold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun ProgramBlock(
	program: ChannelFlowProgram,
	windowStart: LocalDateTime,
	windowMinutes: Int,
	now: LocalDateTime,
	onClick: () -> Unit,
) {
	val airing = !now.isBefore(program.start) && now.isBefore(program.end)
	val startMin = Duration.between(windowStart, program.start).toMinutes().toInt().coerceAtLeast(0)
	val endMin = Duration.between(windowStart, program.end).toMinutes().toInt().coerceAtMost(windowMinutes)
	val durationMin = (endMin - startMin).coerceAtLeast(8)
	Box(
		modifier = Modifier
			.offset(x = pxPerMinute * startMin)
			.width(pxPerMinute * durationMin)
			.height(rowHeight)
			.padding(2.dp)
			.clip(RoundedCornerShape(4.dp))
			.background(if (airing) nowFill else blockFill)
			.clickable(onClick = onClick)
			.padding(start = 3.dp),
	) {
		Box(
			modifier = Modifier
				.width(3.dp)
				.fillMaxHeight()
				.background(if (airing) accent else stripeColor(program)),
		)
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
		) {
			Text(
				text = program.title,
				color = Color(0xFFEEEEEE),
				fontSize = 12.sp,
				fontWeight = FontWeight.Medium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			program.episodeTitle?.let {
				Text(
					text = it,
					color = Color(0xFF9B9B9B),
					fontSize = 10.sp,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun NowLine(offset: Dp, height: Dp) {
	Box(
		modifier = Modifier
			.offset(x = offset)
			.width(2.dp)
			.height(height)
			.background(accent),
	)
}

private fun stripeColor(program: ChannelFlowProgram): Color {
	val haystack = buildString {
		append(program.title)
		append(' ')
		append(program.episodeTitle.orEmpty())
		append(' ')
		append(program.categories.joinToString(" "))
	}.lowercase()
	return when {
		haystack.contains("movie") -> stripeMovie
		haystack.contains("news") -> stripeNews
		haystack.contains("weather") -> stripeWeather
		haystack.contains("music") || haystack.contains("sport") -> stripeMusic
		haystack.contains("series") || haystack.contains("show") -> stripeShow
		else -> stripeDefault
	}
}
