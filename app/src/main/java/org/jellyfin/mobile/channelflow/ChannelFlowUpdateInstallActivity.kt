package org.jellyfin.mobile.channelflow

import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.koin.android.ext.android.inject
import timber.log.Timber

class ChannelFlowUpdateInstallActivity : FragmentActivity() {
	private val updater by inject<ChannelFlowUpdateChecker>()
	private var launchedInstaller = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_update_install)
		if (intent.action == ChannelFlowUpdateChecker.ACTION_INSTALL_STATUS) {
			handleInstallerStatus(intent)
			return
		}
		lifecycleScope.launch {
			runCatching { updater.downloadLatest() }
				.onSuccess { file ->
					if (!updater.openInstaller(this@ChannelFlowUpdateInstallActivity, file)) {
						updater.onInstallFailed(getString(R.string.lbl_update_install_failed))
						finish()
					} else {
						launchedInstaller = true
						if (!isChangingConfigurations) finish()
					}
				}
				.onFailure { error ->
					Timber.e(error, "Unable to download ChannelFlow update")
					updater.onInstallFailed(error.message)
					finish()
				}
		}
		lifecycleScope.launch {
			updater.status.collect { status ->
				val label = findViewById<TextView>(R.id.update_status)
				label.text = when (status) {
					is ChannelFlowUpdateStatus.Downloading ->
						getString(R.string.lbl_downloading_update, status.progress)
					is ChannelFlowUpdateStatus.Failed ->
						status.reason?.takeIf { it.isNotBlank() }
							?: getString(R.string.lbl_update_install_failed)
					else -> getString(R.string.lbl_installing_update)
				}
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		if (intent.action == ChannelFlowUpdateChecker.ACTION_INSTALL_STATUS) {
			handleInstallerStatus(intent)
		}
	}

	override fun onStop() {
		super.onStop()
		if (launchedInstaller && !isChangingConfigurations) finish()
	}

	private fun handleInstallerStatus(intent: Intent) {
		val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
		val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
		val latest = intent.getStringExtra(ChannelFlowUpdateChecker.EXTRA_VERSION).orEmpty()
		when (status) {
			PackageInstaller.STATUS_PENDING_USER_ACTION -> {
				val confirm = confirmationIntent(intent)
				if (confirm != null) {
					confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
					runCatching { startActivity(confirm) }
						.onSuccess {
							launchedInstaller = true
							if (latest.isNotBlank()) updater.onInstallCommitted(latest)
						}
						.onFailure { updater.onInstallFailed(it.message) }
				} else {
					updater.onInstallFailed(message)
					finish()
				}
			}
			PackageInstaller.STATUS_SUCCESS -> {
				updater.onInstallSucceeded()
				finish()
			}
			PackageInstaller.STATUS_FAILURE_ABORTED -> {
				updater.restorePending()
				finish()
			}
			else -> {
				Timber.w("ChannelFlow update install failed status=%s %s", status, message)
				updater.onInstallFailed(message)
				finish()
			}
		}
	}

	private fun confirmationIntent(intent: Intent): Intent? =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
		} else {
			@Suppress("DEPRECATION")
			intent.getParcelableExtra(Intent.EXTRA_INTENT)
		}
}
