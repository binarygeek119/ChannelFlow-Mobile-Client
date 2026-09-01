package org.jellyfin.mobile.channelflow

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val channelFlowModule = module {
	single { ChannelFlowConnectionStore(androidContext()) }
	single { ChannelFlowGuideRepository(get(), lazy { get() }) }
	single { ChannelFlowAccessGuard(androidContext(), get(), get()) }
	single { ChannelFlowClientSession(androidContext(), get(), get(), get()) }
	single { ChannelFlowLogShipper(androidContext(), get(), get()) }
	single { ChannelFlowPairClient() }
	single { ChannelFlowUpdateChecker(androidContext()) }
	single { ChannelFlowReminderScheduler(androidContext()) }
	viewModel { ChannelFlowAppViewModel(get(), get(), get(), get(), get(), get(), get()) }
}
