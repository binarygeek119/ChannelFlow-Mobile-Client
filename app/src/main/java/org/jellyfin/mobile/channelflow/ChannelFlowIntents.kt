package org.jellyfin.mobile.channelflow

import android.content.Context
import android.content.Intent
import org.jellyfin.mobile.MainActivity

fun Context.startChannelFlowPairing() {
	startActivity(
		Intent(this, MainActivity::class.java).apply {
			putExtra(MainActivity.EXTRA_RECONNECT, true)
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
		}
	)
}

fun Context.reloadChannelFlowMain() {
	startActivity(
		Intent(this, MainActivity::class.java).apply {
			addFlags(
				Intent.FLAG_ACTIVITY_NEW_TASK or
					Intent.FLAG_ACTIVITY_CLEAR_TASK or
					Intent.FLAG_ACTIVITY_TASK_ON_HOME
			)
		}
	)
}

fun Context.startChannelFlowPairingFromEmpty() {
	startActivity(
		Intent(this, MainActivity::class.java).apply {
			putExtra(MainActivity.EXTRA_RECONNECT, true)
			addFlags(
				Intent.FLAG_ACTIVITY_NEW_TASK or
					Intent.FLAG_ACTIVITY_CLEAR_TASK or
					Intent.FLAG_ACTIVITY_TASK_ON_HOME
			)
		}
	)
}
