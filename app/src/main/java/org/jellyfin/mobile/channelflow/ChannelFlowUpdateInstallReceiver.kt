package org.jellyfin.mobile.channelflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class ChannelFlowUpdateInstallReceiver : BroadcastReceiver(), KoinComponent {
	private val updater by inject<ChannelFlowUpdateChecker>()

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != ChannelFlowUpdateChecker.ACTION_INSTALL_STATUS) return
		val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
		val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
		val latest = intent.getStringExtra(ChannelFlowUpdateChecker.EXTRA_VERSION).orEmpty()
		when (status) {
			PackageInstaller.STATUS_PENDING_USER_ACTION -> {
				val next = Intent(context, ChannelFlowUpdateInstallActivity::class.java).apply {
					action = ChannelFlowUpdateChecker.ACTION_INSTALL_STATUS
					putExtras(intent)
					addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				}
				runCatching { context.startActivity(next) }
					.onSuccess { if (latest.isNotBlank()) updater.onInstallCommitted(latest) }
					.onFailure { updater.onInstallFailed(it.message ?: message) }
			}
			PackageInstaller.STATUS_SUCCESS -> updater.onInstallSucceeded()
			PackageInstaller.STATUS_FAILURE_ABORTED -> updater.restorePending()
			else -> {
				Timber.w("ChannelFlow update install failed status=%s %s", status, message)
				updater.onInstallFailed(message)
			}
		}
	}
}
