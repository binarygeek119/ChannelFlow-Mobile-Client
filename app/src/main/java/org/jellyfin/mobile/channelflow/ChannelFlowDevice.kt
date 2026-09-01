package org.jellyfin.mobile.channelflow

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import org.jellyfin.mobile.BuildConfig
import java.util.UUID

object ChannelFlowDevice {
	private const val PREFS = "channelflow_client_logs"
	private const val KEY_DEVICE_ID = "device_id"

	fun id(context: Context): String {
		val app = context.applicationContext
		val androidId = ChannelFlowClientLogs.sanitizeDeviceId(
			Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
		)
		if (!androidId.isNullOrBlank()) return androidId
		val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		val stored = ChannelFlowClientLogs.sanitizeDeviceId(prefs.getString(KEY_DEVICE_ID, null))
		if (!stored.isNullOrBlank()) return stored
		val generated = UUID.randomUUID().toString()
		prefs.edit { putString(KEY_DEVICE_ID, generated) }
		return generated
	}

	fun name(context: Context): String {
		val named = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
			Settings.Global.getString(context.applicationContext.contentResolver, Settings.Global.DEVICE_NAME)
		} else {
			null
		}
		return ChannelFlowClientLogs.sanitizeText(named, 80)
			?: ChannelFlowClientLogs.sanitizeText(Build.MODEL, 80)
			?: "Android"
	}

	fun osVersion(): String =
		"Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

	fun appVersion(): String = BuildConfig.VERSION_NAME
}
