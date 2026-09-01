package org.jellyfin.mobile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.jellyfin.mobile.channelflow.ui.ChannelFlowApp
import org.jellyfin.mobile.player.cast.Chromecast
import org.jellyfin.mobile.player.cast.IChromecast
import org.jellyfin.mobile.ui.utils.AppTheme
import org.jellyfin.mobile.utils.BluetoothPermissionHelper
import org.jellyfin.mobile.webapp.RemotePlayerService
import org.koin.android.ext.android.get
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : AppCompatActivity() {
	val mainViewModel: MainViewModel by viewModel()
	val bluetoothPermissionHelper: BluetoothPermissionHelper = BluetoothPermissionHelper(this, get())
	val chromecast: IChromecast = Chromecast()
	var serviceBinder: RemotePlayerService.ServiceBinder? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		installSplashScreen()
		enableEdgeToEdge(
			statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
			navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
		)
		super.onCreate(savedInstanceState)
		setContent {
			AppTheme {
				ChannelFlowApp()
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
	}

	companion object {
		const val EXTRA_RECONNECT = "org.jellyfin.mobile.channelflow.RECONNECT"
	}
}
