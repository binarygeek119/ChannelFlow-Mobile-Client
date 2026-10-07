package org.jellyfin.mobile.channelflow

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object ChannelFlowNetworkMatch {
	data class DeviceAddress(
		val bytes: ByteArray,
		val prefixLength: Int,
	)

	fun sameSubnet(serverHost: String, deviceAddresses: List<DeviceAddress>): Boolean? {
		val server = parseNumericIp(serverHost) ?: return null
		val candidates = deviceAddresses.filter { it.bytes.size == server.size }
		if (candidates.isEmpty()) return false
		return candidates.any { matchesPrefix(server, it.bytes, it.prefixLength) }
	}

	fun parseNumericIp(host: String): ByteArray? {
		val value = host.trim().removePrefix("[").removeSuffix("]")
		if (value.isBlank() || !looksLikeIp(value)) return null
		return runCatching { InetAddress.getByName(value).address }.getOrNull()
	}

	fun matchesPrefix(left: ByteArray, right: ByteArray, prefixLength: Int): Boolean {
		if (left.size != right.size) return false
		val bits = prefixLength.coerceIn(0, left.size * 8)
		val fullBytes = bits / 8
		for (index in 0 until fullBytes) {
			if (left[index] != right[index]) return false
		}
		val remainder = bits % 8
		if (remainder == 0) return true
		val mask = (0xFF shl (8 - remainder)) and 0xFF
		return (left[fullBytes].toInt() and mask) == (right[fullBytes].toInt() and mask)
	}

	fun isReachable(host: String, port: Int, timeoutMs: Int): Boolean {
		if (host.isBlank() || port <= 0) return false
		return runCatching {
			Socket().use { socket ->
				socket.connect(InetSocketAddress(host, port), timeoutMs.coerceAtLeast(1))
				true
			}
		}.getOrDefault(false)
	}

	internal fun looksLikeIp(host: String): Boolean {
		if (host.contains(':')) {
			return host.all { ch ->
				ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F' || ch == ':' || ch == '.'
			}
		}
		val parts = host.split('.')
		if (parts.size != 4) return false
		return parts.all { part ->
			part.isNotEmpty() && part.all { it.isDigit() } && part.toInt() in 0..255
		}
	}
}
