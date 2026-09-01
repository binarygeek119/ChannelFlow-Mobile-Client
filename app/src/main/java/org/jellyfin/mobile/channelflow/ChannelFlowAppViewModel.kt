package org.jellyfin.mobile.channelflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

sealed class ChannelFlowScreen {
	data object Pair : ChannelFlowScreen()
	data object Guide : ChannelFlowScreen()
	data class Player(val channelId: UUID) : ChannelFlowScreen()
	data object Settings : ChannelFlowScreen()
}

data class ChannelFlowUiState(
	val screen: ChannelFlowScreen = ChannelFlowScreen.Pair,
	val pin: String = "",
	val pairing: Boolean = false,
	val pairingError: String? = null,
	val loadingGuide: Boolean = false,
	val guide: List<ChannelGuideItem> = emptyList(),
	val query: String = "",
	val selectedProgram: ChannelFlowProgram? = null,
	val selectedChannelLabel: String? = null,
)

class ChannelFlowAppViewModel(
	private val store: ChannelFlowConnectionStore,
	private val catalog: ChannelFlowGuideRepository,
	private val pairClient: ChannelFlowPairClient,
	private val session: ChannelFlowClientSession,
	val updater: ChannelFlowUpdateChecker,
	val reminders: ChannelFlowReminderScheduler,
	private val access: ChannelFlowAccessGuard,
) : ViewModel() {
	private val _ui = MutableStateFlow(initialState())
	val ui: StateFlow<ChannelFlowUiState> = _ui.asStateFlow()
	val servers = store.state.stateIn(viewModelScope, SharingStarted.Eagerly, store.state.value)
	val updateStatus = updater.status
	val pendingReminder = reminders.pending

	private var pairJob: Job? = null

	init {
		catalog.prefetchLatest()
		viewModelScope.launch { updater.prefetch() }
		viewModelScope.launch {
			access.events.collectLatest { event ->
				when (event) {
					ChannelFlowAccessEvent.PairingRequired -> showPair(force = true)
					ChannelFlowAccessEvent.ReloadGuide -> {
						_ui.value = _ui.value.copy(screen = ChannelFlowScreen.Guide)
						refreshGuide(force = true)
					}
				}
			}
		}
		if (store.isConnected) {
			refreshGuide()
			viewModelScope.launch {
				while (isActive) {
					delay(30_000)
					if (_ui.value.screen is ChannelFlowScreen.Guide) refreshGuide()
				}
			}
		} else {
			startPairing()
		}
	}

	fun filteredGuide(): List<ChannelGuideItem> {
		val q = _ui.value.query.trim()
		val items = _ui.value.guide
		if (q.isEmpty()) return items
		return items.filter { item ->
			item.channel.name.contains(q, ignoreCase = true) ||
				item.channel.number.orEmpty().contains(q, ignoreCase = true) ||
				item.currentProgram?.title.orEmpty().contains(q, ignoreCase = true) ||
				item.programs.any { it.title.contains(q, ignoreCase = true) }
		}
	}

	fun setQuery(value: String) {
		_ui.value = _ui.value.copy(query = value)
	}

	fun showPair(force: Boolean = false) {
		_ui.value = _ui.value.copy(screen = ChannelFlowScreen.Pair)
		if (force || !store.isConnected || pairJob?.isActive != true) startPairing()
	}

	fun showGuide() {
		_ui.value = _ui.value.copy(screen = ChannelFlowScreen.Guide, selectedProgram = null)
		refreshGuide()
	}

	fun showSettings() {
		_ui.value = _ui.value.copy(screen = ChannelFlowScreen.Settings)
	}

	fun play(channelId: UUID) {
		_ui.value = _ui.value.copy(screen = ChannelFlowScreen.Player(channelId), selectedProgram = null)
	}

	fun stopPlayback() {
		_ui.value = _ui.value.copy(screen = ChannelFlowScreen.Guide)
	}

	fun openProgram(program: ChannelFlowProgram, channelLabel: String?) {
		_ui.value = _ui.value.copy(selectedProgram = program, selectedChannelLabel = channelLabel)
	}

	fun dismissProgram() {
		_ui.value = _ui.value.copy(selectedProgram = null, selectedChannelLabel = null)
	}

	fun toggleFavorite(channelId: UUID) {
		store.setFavorite(channelId, !store.isFavorite(channelId))
		refreshGuide()
	}

	fun toggleReminder() {
		val program = _ui.value.selectedProgram ?: return
		reminders.toggle(program, _ui.value.selectedChannelLabel)
	}

	fun acknowledgeReminder(play: Boolean) {
		val reminder = reminders.pending.value ?: return
		reminders.acknowledge(reminder.programId)
		if (play) play(reminder.channelUuid())
	}

	fun startPairing() {
		pairJob?.cancel()
		_ui.value = _ui.value.copy(
			pairing = true,
			pairingError = null,
			pin = "",
			screen = ChannelFlowScreen.Pair,
		)
		pairJob = viewModelScope.launch {
			val result = pairClient.waitForConnection { pin ->
				_ui.value = _ui.value.copy(pin = ChannelFlowPinCrypto.format(pin))
			}
			result.fold(
				onSuccess = { connection ->
					store.save(connection)
					catalog.clear()
					catalog.prefetchLatest()
					_ui.value = _ui.value.copy(
						pairing = false,
						pairingError = null,
						screen = ChannelFlowScreen.Guide,
					)
					refreshGuide(force = true)
				},
				onFailure = { error ->
					val message = when ((error as? ChannelFlowPairException)?.kind) {
						ChannelFlowPairException.Kind.EXPIRED -> "expired"
						else -> error.message ?: "failed"
					}
					_ui.value = _ui.value.copy(pairing = false, pairingError = message)
				},
			)
		}
	}

	fun switchServer(serverId: String) {
		if (!store.setActive(serverId)) return
		catalog.clear()
		catalog.prefetchLatest()
		_ui.value = _ui.value.copy(screen = ChannelFlowScreen.Guide)
		refreshGuide(force = true)
	}

	fun removeServer(serverId: String) {
		val server = store.servers.firstOrNull { it.id == serverId } ?: return
		viewModelScope.launch { session.revoke(server.connection) }
		store.remove(serverId)
		catalog.clear()
		if (!store.isConnected) {
			showPair(force = true)
		} else {
			catalog.prefetchLatest()
			refreshGuide(force = true)
		}
	}

	fun refreshGuide(force: Boolean = false) {
		viewModelScope.launch {
			_ui.value = _ui.value.copy(loadingGuide = true)
			runCatching { catalog.refresh(force) }
			val guide = runCatching { catalog.getGuide() }.getOrDefault(emptyList())
			_ui.value = _ui.value.copy(loadingGuide = false, guide = guide)
		}
	}

	suspend fun channel(id: UUID): ChannelFlowChannel? = catalog.getChannel(id)

	fun zap(currentId: UUID, higher: Boolean): UUID? {
		val next = catalog.adjacentChannel(currentId, higher) ?: return null
		_ui.value = _ui.value.copy(screen = ChannelFlowScreen.Player(next.id))
		return next.id
	}

	private fun initialState(): ChannelFlowUiState =
		if (store.isConnected) ChannelFlowUiState(screen = ChannelFlowScreen.Guide)
		else ChannelFlowUiState(screen = ChannelFlowScreen.Pair)
}
