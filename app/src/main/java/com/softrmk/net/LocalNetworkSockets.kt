package com.softrmk.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Opens TCP sockets to hosts on the local (MFD) network.
 *
 * When the phone is on an MFD Wi-Fi that has no internet access and mobile data is also on,
 * Android keeps cellular as the default network, so a plain `Socket(host, port)` is routed over
 * 4G and never reaches the MFD. Here we pick the network whose subnet contains the target
 * (falling back to any Wi-Fi network) and create the socket through it explicitly.
 */
object LocalNetworkSockets {
    private const val CONNECT_TIMEOUT_MILLIS = 5_000

    fun connect(context: Context, host: String, port: Int): Socket {
        val address = InetAddress.getByName(host)
        val network = findNetworkFor(context, address)
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        try {
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
        return socket
    }

    fun findNetworkFor(context: Context, target: InetAddress): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        var wifiFallback: Network? = null
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val link = cm.getLinkProperties(network) ?: continue
            val onSubnet = link.linkAddresses.any { la ->
                sameSubnet(la.address, target, la.prefixLength)
            }
            if (onSubnet) return network
            if (wifiFallback == null &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            ) {
                wifiFallback = network
            }
        }
        return wifiFallback
    }

    private fun sameSubnet(local: InetAddress, target: InetAddress, prefixLength: Int): Boolean {
        if (local !is Inet4Address || target !is Inet4Address) return false
        if (prefixLength <= 0) return false
        val a = local.address
        val b = target.address
        var bits = prefixLength
        for (i in a.indices) {
            if (bits <= 0) break
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }
}
