package com.softrmk.discovery

import java.io.File
import java.net.InetAddress

object MacAddressResolver {
    fun resolve(address: InetAddress): String? {
        val ip = address.hostAddress ?: return null
        val arp = runCatching { File("/proc/net/arp").readLines() }.getOrDefault(emptyList())
        return arp.drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4 && parts[0] == ip) parts[3].uppercase() else null
            }
            .firstOrNull()
    }
}
