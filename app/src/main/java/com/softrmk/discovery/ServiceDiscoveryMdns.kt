package com.softrmk.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.softrmk.state.PairingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlin.math.min

data class NetworkEndpoint(
    val name: String,
    val address: Inet4Address,
    val macAddress: String?,
)

data class DiscoveredBroker(
    val serviceInstanceName: String,
    val hostName: String,
    val port: Int,
    val brokerUrl: String,
    val interfaceName: String,
    val interfaceAddress: String,
    val interfaceMacAddress: String?,
    val macAddress: String?,
    val ipAddresses: List<String>,
    val txtRecords: Map<String, String>,
) {
    val targetId: String
        get() = PairingStore.normalizeId(macAddress ?: brokerUrl)

    val displayName: String
        get() = serviceLabel(txtRecords["name"] ?: serviceInstanceName)

    companion object {
        fun serviceLabel(value: String): String = value
            .replace("._amqp._tcp.local", "", ignoreCase = true)
            .replace(".local", "", ignoreCase = true)
    }
}

class ServiceDiscoveryMdns(private val context: Context) {
    suspend fun discover(timeoutMillis: Long): List<DiscoveredBroker> = withContext(Dispatchers.IO) {
        val multicastLock = context.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("rmk10-discovery")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }

        try {
            val seen = linkedSetOf<String>()
            val results = mutableListOf<DiscoveredBroker>()
            for (endpoint in defaultInterfaces()) {
                discoverOnInterface(endpoint, timeoutMillis).forEach { broker ->
                    val key = "${broker.interfaceName}|${broker.serviceInstanceName}|${broker.hostName}|${broker.port}"
                    if (seen.add(key)) {
                        results += broker
                    }
                }
            }
            results
        } finally {
            multicastLock?.release()
        }
    }

    fun defaultInterfaces(): List<NetworkEndpoint> {
        val wireless = mutableListOf<NetworkEndpoint>()
        val fallback = mutableListOf<NetworkEndpoint>()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.let(Collections::list).orEmpty()
        interfaces.forEach { nic ->
            if (!nic.isUp || nic.isLoopback) return@forEach
            val mac = nic.hardwareAddress?.takeIf { it.isNotEmpty() }?.joinToString(":") { "%02X".format(it) }
            Collections.list(nic.inetAddresses).filterIsInstance<Inet4Address>().forEach { inet ->
                if (inet.isLoopbackAddress || inet.isLinkLocalAddress) return@forEach
                val endpoint = NetworkEndpoint(nic.displayName ?: nic.name, inet, mac)
                fallback += endpoint
                if (nic.name.contains("wlan", true) || nic.displayName?.contains("wi-fi", true) == true) {
                    wireless += endpoint
                }
            }
        }
        return if (wireless.isNotEmpty()) wireless else fallback
    }

    private fun discoverOnInterface(endpoint: NetworkEndpoint, timeoutMillis: Long): List<DiscoveredBroker> {
        val socket = MulticastSocket(null)
        socket.reuseAddress = true
        socket.soTimeout = min(timeoutMillis, 750L).toInt()
        socket.networkInterface = NetworkInterface.getByInetAddress(endpoint.address)
        socket.bind(InetSocketAddress(endpoint.address, 0))
        socket.joinGroup(InetSocketAddress(MDNS_ADDRESS, MDNS_PORT), socket.networkInterface)

        try {
            val aggregate = DnsAggregate()
            val query = buildQuery(SERVICE_TYPE, DnsRecordType.PTR)
            socket.send(DatagramPacket(query, query.size, MDNS_ADDRESS, MDNS_PORT))
            collectReplies(socket, timeoutMillis, aggregate)

            aggregate.ptrTargets(SERVICE_TYPE)
                .filterNot(aggregate::hasSrv)
                .forEach { serviceName ->
                    val followUp = buildQuery(serviceName, DnsRecordType.ANY)
                    socket.send(DatagramPacket(followUp, followUp.size, MDNS_ADDRESS, MDNS_PORT))
                }

            collectReplies(socket, 750L, aggregate)

            return aggregate.ptrTargets(SERVICE_TYPE).mapNotNull { serviceName ->
                val srv = aggregate.srv(serviceName) ?: return@mapNotNull null
                val txt = aggregate.txt(serviceName)
                if (txt["raymarine"]?.equals("true", true) == false) {
                    return@mapNotNull null
                }
                val addresses = aggregate.addresses(srv.target).map(InetAddress::getHostAddress).distinct()
                if (addresses.isEmpty()) {
                    return@mapNotNull null
                }
                val primaryAddress = InetAddress.getByName(addresses.first())
                DiscoveredBroker(
                    serviceInstanceName = serviceName,
                    hostName = srv.target,
                    port = srv.port,
                    brokerUrl = "amqp:tcp:${addresses.first()}:${srv.port}",
                    interfaceName = endpoint.name,
                    interfaceAddress = endpoint.address.hostAddress ?: "",
                    interfaceMacAddress = endpoint.macAddress,
                    macAddress = MacAddressResolver.resolve(primaryAddress),
                    ipAddresses = addresses,
                    txtRecords = txt,
                )
            }
        } finally {
            runCatching { socket.leaveGroup(InetSocketAddress(MDNS_ADDRESS, MDNS_PORT), socket.networkInterface) }
            socket.close()
        }
    }

    private fun collectReplies(socket: MulticastSocket, windowMillis: Long, aggregate: DnsAggregate) {
        val deadline = System.currentTimeMillis() + windowMillis
        while (System.currentTimeMillis() < deadline) {
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                aggregate.add(DnsMessage.parse(packet.data.copyOf(packet.length)))
            } catch (_: SocketTimeoutException) {
                break
            } catch (_: Throwable) {
            }
        }
    }

    private fun buildQuery(name: String, type: DnsRecordType): ByteArray {
        val stream = ByteArrayOutputStream()
        fun writeU16(value: Int) {
            stream.write((value shr 8) and 0xFF)
            stream.write(value and 0xFF)
        }
        writeU16(0)
        writeU16(0)
        writeU16(1)
        writeU16(0)
        writeU16(0)
        writeU16(0)
        name.trim('.').split('.').filter { it.isNotBlank() }.forEach { label ->
            val bytes = label.toByteArray(StandardCharsets.US_ASCII)
            stream.write(bytes.size)
            stream.write(bytes)
        }
        stream.write(0)
        writeU16(type.code)
        writeU16(1)
        return stream.toByteArray()
    }

    private enum class DnsRecordType(val code: Int) {
        A(1), PTR(12), TXT(16), AAAA(28), SRV(33), ANY(255)
    }

    private class DnsAggregate {
        private val ptr = linkedMapOf<String, MutableSet<String>>()
        private val srv = linkedMapOf<String, SrvRecord>()
        private val txt = linkedMapOf<String, Map<String, String>>()
        private val a = linkedMapOf<String, MutableSet<InetAddress>>()

        fun add(message: DnsMessage) {
            (message.answers + message.authorities + message.additionals).forEach { record ->
                when (record) {
                    is PtrRecord -> ptr.getOrPut(record.name) { linkedSetOf() }.add(record.target)
                    is SrvRecord -> srv[record.name] = record
                    is TxtRecord -> txt[record.name] = record.values
                    is ARecord -> a.getOrPut(record.name) { linkedSetOf() }.add(record.address)
                }
            }
        }

        fun ptrTargets(name: String): List<String> = ptr[name]?.toList().orEmpty()
        fun hasSrv(name: String): Boolean = srv.containsKey(name)
        fun srv(name: String): SrvRecord? = srv[name]
        fun txt(name: String): Map<String, String> = txt[name].orEmpty()
        fun addresses(name: String): List<InetAddress> = a[name]?.toList().orEmpty()
    }

    private class DnsMessage(
        val answers: List<DnsRecord>,
        val authorities: List<DnsRecord>,
        val additionals: List<DnsRecord>,
    ) {
        companion object {
            fun parse(bytes: ByteArray): DnsMessage {
                val reader = DnsReader(bytes)
                reader.readU16()
                reader.readU16()
                val qd = reader.readU16()
                val an = reader.readU16()
                val ns = reader.readU16()
                val ar = reader.readU16()
                repeat(qd) {
                    reader.readName()
                    reader.readU16()
                    reader.readU16()
                }
                return DnsMessage(
                    answers = readRecords(reader, an),
                    authorities = readRecords(reader, ns),
                    additionals = readRecords(reader, ar),
                )
            }

            private fun readRecords(reader: DnsReader, count: Int): List<DnsRecord> = buildList {
                repeat(count) {
                    val name = reader.readName()
                    val type = reader.readU16()
                    reader.readU16()
                    reader.readU32()
                    val length = reader.readU16()
                    val dataStart = reader.position
                    val record = when (type) {
                        DnsRecordType.PTR.code -> PtrRecord(name, reader.readName())
                        DnsRecordType.SRV.code -> {
                            reader.readU16()
                            reader.readU16()
                            val port = reader.readU16()
                            SrvRecord(name, reader.readName(), port)
                        }
                        DnsRecordType.TXT.code -> {
                            val end = reader.position + length
                            val values = linkedMapOf<String, String>()
                            while (reader.position < end) {
                                val itemLength = reader.readByte()
                                if (itemLength == 0) continue
                                val item = String(reader.readBytes(itemLength), StandardCharsets.UTF_8)
                                val split = item.split('=', limit = 2)
                                values[split[0]] = split.getOrElse(1) { "" }
                            }
                            TxtRecord(name, values)
                        }
                        DnsRecordType.A.code -> {
                            if (length == 4) {
                                ARecord(name, InetAddress.getByAddress(reader.readBytes(4)))
                            } else {
                                null
                            }
                        }
                        else -> null
                    }
                    reader.position = dataStart + length
                    if (record != null) add(record)
                }
            }
        }
    }

    private class DnsReader(private val buffer: ByteArray) {
        var position: Int = 0

        fun readByte(): Int = buffer[position++].toInt() and 0xFF

        fun readBytes(count: Int): ByteArray = buffer.copyOfRange(position, position + count).also { position += count }

        fun readU16(): Int {
            val value = ((buffer[position].toInt() and 0xFF) shl 8) or (buffer[position + 1].toInt() and 0xFF)
            position += 2
            return value
        }

        fun readU32(): Long {
            val value = ByteBuffer.wrap(buffer, position, 4).int.toLong() and 0xFFFFFFFFL
            position += 4
            return value
        }

        fun readName(): String {
            val labels = mutableListOf<String>()
            var jumped = false
            var originalPosition = position
            while (true) {
                val length = buffer[position].toInt() and 0xFF
                if (length == 0) {
                    position += 1
                    break
                }
                if ((length and 0xC0) == 0xC0) {
                    val pointer = ((buffer[position].toInt() and 0x3F) shl 8) or (buffer[position + 1].toInt() and 0xFF)
                    if (!jumped) {
                        originalPosition = position + 2
                    }
                    position = pointer
                    jumped = true
                    continue
                }
                position += 1
                labels += String(buffer, position, length, StandardCharsets.US_ASCII)
                position += length
            }
            if (jumped) {
                position = originalPosition
            }
            return labels.joinToString(".")
        }
    }

    private sealed interface DnsRecord { val name: String }
    private data class PtrRecord(override val name: String, val target: String) : DnsRecord
    private data class TxtRecord(override val name: String, val values: Map<String, String>) : DnsRecord
    private data class SrvRecord(override val name: String, val target: String, val port: Int) : DnsRecord
    private data class ARecord(override val name: String, val address: InetAddress) : DnsRecord

    companion object {
        private const val MDNS_PORT = 5353
        private const val SERVICE_TYPE = "_amqp._tcp.local"
        private val MDNS_ADDRESS: InetAddress = InetAddress.getByName("224.0.0.251")
    }
}
