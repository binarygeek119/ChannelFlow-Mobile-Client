package org.jellyfin.mobile

import android.app.Application
import org.jellyfin.mobile.app.apiModule
import org.jellyfin.mobile.app.applicationModule
import org.jellyfin.mobile.channelflow.ChannelFlowClientSession
import org.jellyfin.mobile.channelflow.ChannelFlowLogShipper
import org.jellyfin.mobile.channelflow.channelFlowModule
import org.jellyfin.mobile.data.databaseModule
import org.jellyfin.mobile.utils.JellyTree
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.fragment.koin.fragmentFactory
import org.koin.core.context.startKoin
import timber.log.Timber

@Suppress("unused")
class JellyfinApplication : Application() {
	override fun onCreate() {
		super.onCreate()

		Timber.plant(JellyTree())

		startKoin {
			androidContext(this@JellyfinApplication)
			fragmentFactory()

			modules(
				applicationModule,
				apiModule,
				databaseModule,
				channelFlowModule,
			)
		}

		get<ChannelFlowLogShipper>().start()
		get<ChannelFlowClientSession>().start()
	}
}
