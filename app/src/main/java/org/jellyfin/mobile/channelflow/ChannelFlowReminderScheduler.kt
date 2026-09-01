package org.jellyfin.mobile.channelflow

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID

class ChannelFlowReminderScheduler(
	context: Context,
) {
	private val app = context.applicationContext
	private val file = app.filesDir.resolve("channelflow_reminders.json")
	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val jobs = mutableMapOf<String, Job>()

	private val _reminders = MutableStateFlow(load())
	val reminders: StateFlow<List<ChannelFlowReminder>> = _reminders.asStateFlow()

	private val _pending = MutableStateFlow<ChannelFlowReminder?>(null)
	val pending: StateFlow<ChannelFlowReminder?> = _pending.asStateFlow()

	init {
		pruneExpired()
		checkDue()
		_reminders.value.forEach(::arm)
	}

	fun isSet(programId: UUID): Boolean =
		_reminders.value.any { it.programId == programId.toString() }

	fun toggle(program: ChannelFlowProgram, channelLabel: String?): Boolean {
		val existing = _reminders.value.firstOrNull { it.programId == program.id.toString() }
		if (existing != null) {
			remove(existing.programId)
			return false
		}
		val reminder = ChannelFlowReminder.fromProgram(program, channelLabel) ?: return false
		commit(_reminders.value.filterNot { it.programId == reminder.programId } + reminder)
		arm(reminder)
		return true
	}

	fun acknowledge(programId: String) {
		if (_pending.value?.programId == programId) _pending.value = null
		remove(programId)
		checkDue()
	}

	fun checkDue() {
		val now = System.currentTimeMillis()
		val due = _reminders.value
			.filter { it.isDue(now) }
			.minByOrNull { it.fireAtEpochMilli }
		if (due != null && _pending.value == null) {
			jobs.remove(due.programId)?.cancel()
			_pending.value = due
		}
	}

	private fun remove(programId: String) {
		jobs.remove(programId)?.cancel()
		commit(_reminders.value.filterNot { it.programId == programId })
	}

	private fun pruneExpired() {
		val now = System.currentTimeMillis()
		val kept = _reminders.value.filterNot { it.isExpired(now) }
		if (kept.size != _reminders.value.size) commit(kept)
	}

	private fun arm(reminder: ChannelFlowReminder) {
		jobs.remove(reminder.programId)?.cancel()
		val delayMs = reminder.fireAtEpochMilli - System.currentTimeMillis()
		if (delayMs <= 0L) {
			checkDue()
			return
		}
		jobs[reminder.programId] = scope.launch {
			delay(delayMs)
			checkDue()
		}
	}

	private fun load(): List<ChannelFlowReminder> = runCatching {
		if (!file.exists()) return emptyList()
		json.decodeFromString<ChannelFlowReminderFile>(file.readText()).reminders
	}.onFailure { error ->
		Timber.w(error, "Unable to read ChannelFlow reminders")
	}.getOrDefault(emptyList())

	private fun commit(next: List<ChannelFlowReminder>) {
		_reminders.value = next
		runCatching {
			file.writeText(json.encodeToString(ChannelFlowReminderFile(next)))
		}.onFailure { error ->
			Timber.w(error, "Unable to save ChannelFlow reminders")
		}
	}
}

@Serializable
private data class ChannelFlowReminderFile(
	val reminders: List<ChannelFlowReminder> = emptyList(),
)
