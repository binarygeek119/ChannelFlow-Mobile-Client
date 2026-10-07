package org.jellyfin.mobile.channelflow

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.Inet4Address
import java.net.Inet6Address

enum class ChannelFlowNetworkKind {
	LOCAL,
	PUBLIC,
	PRIMARY,
}

data class ChannelFlowResolvedEndpoint(
	val endpoint: ChannelFlowEndpoint,
	val kind: ChannelFlowNetworkKind,
)

class ChannelFlowEndpointResolver(
	context: Context,
) {
	private val app = context.applicationContext
	private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val cacheLock = Any()
	private val _networkGeneration = MutableStateFlow(0)
	val networkGeneration: StateFlow<Int> = _networkGeneration.asStateFlow()

	private var cachedKey: String? = null
	private var cached: ChannelFlowResolvedEndpoint? = null
	private var forcedKind: ChannelFlowNetworkKind? = null
	private var probingKey: String? = null
	private var debounceJob: Job? = null

	private val callback = object : ConnectivityManager.NetworkCallback() {
		override fun onAvailable(network: Network) = onNetworkChanged()
		override fun onLost(network: Network) = onNetworkChanged()
		override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onNetworkChanged()
		override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
			onNetworkChanged()
	}

	init {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			runCatching { connectivity.registerDefaultNetworkCallback(callback) }
				.onFailure { Timber.w(it, "Unable to watch network changes for ChannelFlow endpoints") }
		}
	}

	fun resolve(connection: ChannelFlowConnection): ChannelFlowResolvedEndpoint {
		if (!connection.hasVariants()) {
			return ChannelFlowResolvedEndpoint(connection.primaryEndpoint(), ChannelFlowNetworkKind.PRIMARY)
				.also { remember(connection, it) }
		}

		synchronized(cacheLock) {
			cached?.takeIf { cachedKey == cacheKey(connection) }?.let { return it }
		}

		val kind = synchronized(cacheLock) { forcedKind } ?: classify(connection, currentDeviceAddresses())
		val resolved = ChannelFlowResolvedEndpoint(connection.endpointFor(kind), kind)
		remember(connection, resolved)
		if (synchronized(cacheLock) { forcedKind } == null &&
			kind != ChannelFlowNetworkKind.LOCAL &&
			needsProbe(connection)
		) {
			requestProbe(connection)
		}
		return resolved
	}

	fun preferFallback(connection: ChannelFlowConnection): ChannelFlowResolvedEndpoint? {
		val current = resolve(connection)
		val other = when (current.kind) {
			ChannelFlowNetworkKind.LOCAL ->
				connection.public?.let { ChannelFlowResolvedEndpoint(it, ChannelFlowNetworkKind.PUBLIC) }
			ChannelFlowNetworkKind.PUBLIC ->
				connection.local?.let { ChannelFlowResolvedEndpoint(it, ChannelFlowNetworkKind.LOCAL) }
			ChannelFlowNetworkKind.PRIMARY -> null
		} ?: return null
		synchronized(cacheLock) {
			forcedKind = other.kind
			cached = other
			cachedKey = cacheKey(connection)
		}
		Timber.i("ChannelFlow falling back to %s endpoint %s", other.kind, other.endpoint.baseUrl)
		return other
	}

	suspend fun <T> call(
		connection: ChannelFlowConnection,
		block: suspend (ChannelFlowEndpoint) -> T,
	): T {
		val first = resolve(connection)
		return try {
			block(first.endpoint)
		} catch (error: CancellationException) {
			throw error
		} catch (error: ChannelFlowUnauthorizedException) {
			throw error
		} catch (error: Exception) {
			val fallback = preferFallback(connection) ?: throw error
			Timber.w(error, "ChannelFlow %s endpoint failed; trying %s", first.kind, fallback.kind)
			block(fallback.endpoint)
		}
	}

	internal fun classify(
		connection: ChannelFlowConnection,
		addresses: List<ChannelFlowNetworkMatch.DeviceAddress>,
	): ChannelFlowNetworkKind {
		val local = connection.local
		val public = connection.public
		when {
			local == null && public == null -> return ChannelFlowNetworkKind.PRIMARY
			local == null -> return ChannelFlowNetworkKind.PUBLIC
			public == null -> return ChannelFlowNetworkKind.LOCAL
		}
		val host = ChannelFlowUrls.hostOf(local.baseUrl) ?: return ChannelFlowNetworkKind.PUBLIC
		return when (ChannelFlowNetworkMatch.sameSubnet(host, addresses)) {
			true -> ChannelFlowNetworkKind.LOCAL
			false -> ChannelFlowNetworkKind.PUBLIC
			null -> ChannelFlowNetworkKind.PUBLIC
		}
	}

	private fun needsProbe(connection: ChannelFlowConnection): Boolean {
		val local = connection.local ?: return false
		val host = ChannelFlowUrls.hostOf(local.baseUrl) ?: return false
		return ChannelFlowNetworkMatch.sameSubnet(host, currentDeviceAddresses()) == null
	}

	private fun requestProbe(connection: ChannelFlowConnection) {
		val key = cacheKey(connection)
		synchronized(cacheLock) {
			if (probingKey == key) return
			probingKey = key
		}
		scope.launch {
			try {
				val local = connection.local ?: return@launch
				val host = ChannelFlowUrls.hostOf(local.baseUrl) ?: return@launch
				val port = ChannelFlowUrls.portOf(local.baseUrl)
				if (!ChannelFlowNetworkMatch.isReachable(host, port, PROBE_TIMEOUT_MS)) return@launch
				synchronized(cacheLock) {
					forcedKind = ChannelFlowNetworkKind.LOCAL
					cached = null
					cachedKey = null
				}
				bumpGeneration()
				Timber.i("ChannelFlow local endpoint reachable; switching to LAN")
			} finally {
				synchronized(cacheLock) {
					if (probingKey == key) probingKey = null
				}
			}
		}
	}

	private fun remember(connection: ChannelFlowConnection, resolved: ChannelFlowResolvedEndpoint) {
		synchronized(cacheLock) {
			cachedKey = cacheKey(connection)
			cached = resolved
		}
	}

	private fun onNetworkChanged() {
		synchronized(cacheLock) {
			cached = null
			cachedKey = null
			forcedKind = null
			probingKey = null
		}
		debounceJob?.cancel()
		debounceJob = scope.launch {
			delay(NETWORK_DEBOUNCE_MS)
			bumpGeneration()
		}
	}

	private fun bumpGeneration() {
		_networkGeneration.value = _networkGeneration.value + 1
	}

	private fun cacheKey(connection: ChannelFlowConnection): String =
		listOf(
			connection.baseUrl,
			connection.local?.baseUrl.orEmpty(),
			connection.public?.baseUrl.orEmpty(),
		).joinToString("\n")

	@Suppress("DEPRECATION")
	private fun currentDeviceAddresses(): List<ChannelFlowNetworkMatch.DeviceAddress> {
		val networks = connectivity.allNetworks.toList()
			.ifEmpty { listOfNotNull(connectivity.activeNetwork) }
		return networks.flatMap { network ->
			val props = connectivity.getLinkProperties(network) ?: return@flatMap emptyList()
			props.linkAddresses.mapNotNull { link ->
				val address = link.address ?: return@mapNotNull null
				if (address.isLoopbackAddress || address.isAnyLocalAddress) return@mapNotNull null
				if (address is Inet6Address && address.isLinkLocalAddress) return@mapNotNull null
				if (address is Inet4Address && address.isLinkLocalAddress) return@mapNotNull null
				ChannelFlowNetworkMatch.DeviceAddress(address.address, link.prefixLength)
			}
		}
	}

	companion object {
		private const val PROBE_TIMEOUT_MS = 1_500
		private const val NETWORK_DEBOUNCE_MS = 300L
	}
}

internal class ChannelFlowUnauthorizedException(message: String) : Exception(message)
