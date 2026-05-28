package com.softrmk.protocol

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.min

class RemoteKeyboardClient(
    brokerUri: String,
    private val topicAddress: String = RemoteKeyboardProtocol.OBSERVED_KEYBOARD_ADDRESS,
    private val onBrokerEvent: ((String) -> Unit)? = null,
    private val onConnectionClosed: (() -> Unit)? = null,
) : Closeable {
    private val connection = Qpid010Connection(brokerUri, onBrokerEvent, onConnectionClosed)

    fun connect() {
        connection.connect()
    }

    fun send(envelope: RemoteKeyboardEnvelope) {
        connection.connect()
        connection.sendMessage(topicAddress.trim(), envelope.messageName, envelope.toWireMap())
    }

    override fun close() {
        connection.close()
    }

    private class Qpid010Connection(
        private val brokerUri: String,
        private val onBrokerEvent: ((String) -> Unit)?,
        private val onConnectionClosed: (() -> Unit)?,
    ) : Closeable {
        private var socket: Socket? = null
        private var input: InputStream? = null
        private var output: OutputStream? = null
        @Volatile
        private var connected = false
        private var nextOutgoingCommandId = 0
        private var negotiatedMaxFrameSize = 65_535
        private val declaredTopicExchanges = mutableSetOf<String>()
        private val sessionName = "rk-sender-" + java.util.UUID.randomUUID().toString().replace("-", "")
        private var readerThread: Thread? = null
        @Volatile
        private var closeNotified = false

        fun connect() {
            if (connected) return
            closeNotified = false
            val (host, port) = parseBrokerEndpoint(brokerUri)
            val sock = Socket(host, port)
            socket = sock
            input = sock.getInputStream()
            output = sock.getOutputStream()

            writeRaw(byteArrayOf('A'.code.toByte(), 'M'.code.toByte(), 'Q'.code.toByte(), 'P'.code.toByte(), 1, 1, 0, 10))
            val peerHeader = readExactly(8)
            require(peerHeader.copyOfRange(0, 4).contentEquals(byteArrayOf('A'.code.toByte(), 'M'.code.toByte(), 'Q'.code.toByte(), 'P'.code.toByte()))) {
                "Broker did not return an AMQP 0-10 protocol header."
            }

            val start = readFrameUntil { it.type == SEGMENT_TYPE_CONTROL && it.channel == 0 && it.classId == 1 && it.methodId == 1 }
            val mechanism = selectMechanism(decodeConnectionStartMechanisms(start.payload))
            writeMethodFrame(SEGMENT_TYPE_CONTROL, 0, 1, 2, encodeConnectionStartOk(mechanism))

            val tune = readFrameUntil { it.type == SEGMENT_TYPE_CONTROL && it.channel == 0 && it.classId == 1 && it.methodId == 5 }
            val tuneValues = decodeConnectionTune(tune.payload)
            negotiatedMaxFrameSize = if (tuneValues.maxFrameSize == 0) negotiatedMaxFrameSize else min(negotiatedMaxFrameSize, tuneValues.maxFrameSize)
            val heartbeatMax = if (tuneValues.heartbeatMax == 0) UShort.MAX_VALUE.toInt() else tuneValues.heartbeatMax
            val heartbeat = maxOf(tuneValues.heartbeatMin, min(RemoteKeyboardProtocol.HEARTBEAT_OPTION_VALUE, heartbeatMax))

            writeMethodFrame(SEGMENT_TYPE_CONTROL, 0, 1, 6, encodeConnectionTuneOk(if (tuneValues.channelMax == 0) 32_767 else tuneValues.channelMax, negotiatedMaxFrameSize, heartbeat))
            writeMethodFrame(SEGMENT_TYPE_CONTROL, 0, 1, 7, encodeConnectionOpen())
            readFrameUntil { it.type == SEGMENT_TYPE_CONTROL && it.channel == 0 && it.classId == 1 && it.methodId == 8 }

            writeMethodFrame(SEGMENT_TYPE_CONTROL, SESSION_CHANNEL, 2, 1, encodeSessionAttach(sessionName))
            readFrameUntil { it.type == SEGMENT_TYPE_CONTROL && it.channel == SESSION_CHANNEL && it.classId == 2 && it.methodId == 2 }

            writeMethodFrame(SEGMENT_TYPE_CONTROL, SESSION_CHANNEL, 2, 7, encodeSessionCommandPoint(nextOutgoingCommandId, 0))
            runCatching {
                readFrameUntil { it.type == SEGMENT_TYPE_CONTROL && it.channel == SESSION_CHANNEL && it.classId == 2 && it.methodId == 7 }
            }

            connected = true
            startBackgroundReader()
        }

        fun sendMessage(destination: String, messageName: String, contentMap: Map<String, Any>) {
            check(connected) { "Connection is not open." }
            ensureTopicExchange(destination)
            nextOutgoingCommandId += 1
            val body = encodeApplicationMap(contentMap)
            writeMethodFrame(
                segmentType = SEGMENT_TYPE_COMMAND,
                channel = SESSION_CHANNEL,
                classId = 4,
                methodId = 1,
                payload = encodeMessageTransfer(destination),
                firstSegment = true,
                lastSegment = false,
            )
            writeHeaderFrame(SESSION_CHANNEL, encodeMessageHeader(body.size))
            writeBodyFrame(SESSION_CHANNEL, body)
        }

        private fun ensureTopicExchange(exchangeName: String) {
            if (exchangeName.isBlank() || !declaredTopicExchanges.add(exchangeName)) {
                return
            }
            nextOutgoingCommandId += 1
            writeMethodFrame(SEGMENT_TYPE_COMMAND, SESSION_CHANNEL, 7, 1, encodeExchangeDeclare(exchangeName))
        }

        override fun close() {
            val wasConnected = connected
            runCatching {
                if (wasConnected) {
                    writeMethodFrame(SEGMENT_TYPE_CONTROL, SESSION_CHANNEL, 2, 3, encodeSessionDetach(sessionName))
                    writeMethodFrame(SEGMENT_TYPE_CONTROL, 0, 1, 0x0B, encodeConnectionClose())
                }
            }
            connected = false
            cleanupTransport()
        }

        private fun startBackgroundReader() {
            if (readerThread != null) {
                return
            }

            readerThread = Thread {
                try {
                    while (connected) {
                        val frame = readFrame()
                        if (frame.type == SEGMENT_TYPE_HEARTBEAT) {
                            continue
                        }
                        describeIncomingFrame(frame)?.let { onBrokerEvent?.invoke(it) }
                    }
                } catch (_: InterruptedException) {
                } catch (_: EOFException) {
                    handleConnectionLoss("qpidd: connection closed by broker")
                } catch (error: Throwable) {
                    handleConnectionLoss("qpidd: reader error: ${error.message ?: error::class.java.simpleName}")
                }
            }.apply {
                isDaemon = true
                name = "qpidd-reader"
                start()
            }
        }

        private fun handleConnectionLoss(message: String) {
            if (!connected) {
                return
            }

            connected = false
            onBrokerEvent?.invoke(message)
            cleanupTransport()
            notifyClosedOnce()
        }

        private fun cleanupTransport() {
            readerThread?.interrupt()
            readerThread = null
            runCatching { input?.close() }
            runCatching { output?.close() }
            runCatching { socket?.close() }
            input = null
            output = null
            socket = null
        }

        private fun notifyClosedOnce() {
            if (closeNotified) {
                return
            }
            closeNotified = true
            onConnectionClosed?.invoke()
        }

        private fun readFrameUntil(predicate: (QpidFrame) -> Boolean): QpidFrame {
            while (true) {
                val frame = readFrame()
                if (frame.type == SEGMENT_TYPE_HEARTBEAT) continue
                if (predicate(frame)) return frame
                if (frame.type == SEGMENT_TYPE_COMMAND && frame.channel == SESSION_CHANNEL && frame.classId == 3 && frame.methodId == 3) {
                    throw IllegalStateException(decodeExecutionException(frame.payload))
                }
                if (frame.type == SEGMENT_TYPE_CONTROL && frame.channel == 0 && frame.classId == 1 && frame.methodId == 0x0B) {
                    error("Broker closed the AMQP 0-10 connection during setup.")
                }
            }
        }

        private fun readFrame(): QpidFrame {
            val header = readExactly(12)
            val type = header[1].toInt() and 0xFF
            val size = readU16(header, 2)
            val channel = readU16(header, 6)
            require(size >= 12) { "Invalid frame size $size." }
            val remainder = readExactly(size - 12)
            val classId = if (type == SEGMENT_TYPE_CONTROL || type == SEGMENT_TYPE_COMMAND) remainder[0].toInt() and 0xFF else null
            val methodId = if (type == SEGMENT_TYPE_CONTROL || type == SEGMENT_TYPE_COMMAND) remainder[1].toInt() and 0xFF else null
            val payload = if (classId != null) remainder.copyOfRange(2, remainder.size) else remainder
            return QpidFrame(type, channel, classId, methodId, payload)
        }

        private fun writeMethodFrame(
            segmentType: Int,
            channel: Int,
            classId: Int,
            methodId: Int,
            payload: ByteArray,
            firstSegment: Boolean = true,
            lastSegment: Boolean = true,
            firstFrame: Boolean = true,
            lastFrame: Boolean = true,
        ) {
            val frameSize = 12 + 2 + payload.size
            val buffer = ByteArray(frameSize)
            buffer[0] = composeFlags(firstSegment, lastSegment, firstFrame, lastFrame).toByte()
            buffer[1] = segmentType.toByte()
            writeU16(buffer, 2, frameSize)
            buffer[5] = if (segmentType == SEGMENT_TYPE_CONTROL) 0 else 1
            writeU16(buffer, 6, channel)
            buffer[12] = classId.toByte()
            buffer[13] = methodId.toByte()
            payload.copyInto(buffer, 14)
            writeRaw(buffer)
        }

        private fun writeHeaderFrame(channel: Int, payload: ByteArray) {
            val frameSize = 12 + payload.size
            val buffer = ByteArray(frameSize)
            buffer[0] = composeFlags(firstSegment = false, lastSegment = false, firstFrame = true, lastFrame = true).toByte()
            buffer[1] = SEGMENT_TYPE_HEADER.toByte()
            writeU16(buffer, 2, frameSize)
            buffer[5] = 1
            writeU16(buffer, 6, channel)
            payload.copyInto(buffer, 12)
            writeRaw(buffer)
        }

        private fun writeBodyFrame(channel: Int, payload: ByteArray) {
            val frameSize = 12 + payload.size
            val buffer = ByteArray(frameSize)
            buffer[0] = composeFlags(firstSegment = false, lastSegment = true, firstFrame = true, lastFrame = true).toByte()
            buffer[1] = SEGMENT_TYPE_BODY.toByte()
            writeU16(buffer, 2, frameSize)
            buffer[5] = 1
            writeU16(buffer, 6, channel)
            payload.copyInto(buffer, 12)
            writeRaw(buffer)
        }

        private fun writeRaw(bytes: ByteArray) {
            output?.write(bytes)
            output?.flush()
        }

        private fun readExactly(size: Int): ByteArray {
            val stream = input ?: error("No active network stream.")
            val buffer = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = stream.read(buffer, offset, size - offset)
                if (read <= 0) throw EOFException("Unexpected end of stream.")
                offset += read
            }
            return buffer
        }

        private fun encodeConnectionStartOk(mechanism: String): ByteArray = BigEndianWriter().apply {
            writeU16Le(0x000F)
            writeFieldTable(
                linkedMapOf(
                    "qpid.session_flow" to 1,
                    "qpid.client_process" to "RMK10Android",
                    "qpid.client_pid" to android.os.Process.myPid(),
                    "qpid.client_ppid" to android.os.Process.myPid(),
                )
            )
            writeStr8(mechanism)
            writeVbin32(if (mechanism == "ANONYMOUS") byteArrayOf() else byteArrayOf(0, 0))
            writeStr8("en_US")
        }.toByteArray()

        private fun encodeConnectionTuneOk(channelMax: Int, maxFrameSize: Int, heartbeat: Int): ByteArray = BigEndianWriter().apply {
            writeU16Le(0x0007)
            writeU16(channelMax)
            writeU16(maxFrameSize)
            writeU16(heartbeat)
        }.toByteArray()

        private fun encodeConnectionOpen(): ByteArray = BigEndianWriter().apply {
            writeU16Le(0x0003)
            writeStr8("")
            writeStr16Array(emptyList())
        }.toByteArray()

        private fun encodeConnectionClose(): ByteArray = BigEndianWriter().apply {
            writeU16Le(0x0003)
            writeU16(200)
            writeStr8("OK")
        }.toByteArray()

        private fun encodeSessionAttach(sessionName: String): ByteArray = BigEndianWriter().apply {
            writeU16Le(0x0001)
            writeVbin16(sessionName.toByteArray(StandardCharsets.UTF_8))
        }.toByteArray()

        private fun encodeSessionCommandPoint(commandId: Int, offset: Long): ByteArray = BigEndianWriter().apply {
            writeU16Le(0x0003)
            writeU32(commandId.toLong())
            writeU64(offset)
        }.toByteArray()

        private fun encodeSessionDetach(sessionName: String): ByteArray = BigEndianWriter().apply {
            writeU16Le(0x0001)
            writeVbin16(sessionName.toByteArray(StandardCharsets.UTF_8))
        }.toByteArray()

        private fun encodeMessageTransfer(destination: String): ByteArray = BigEndianWriter().apply {
            writeModelMethodHeader(sync = false)
            writeU16Le(0x0007)
            writeStr8(destination)
            writeU8(0)
            writeU8(0)
        }.toByteArray()

        private fun encodeExchangeDeclare(exchangeName: String): ByteArray = BigEndianWriter().apply {
            writeModelMethodHeader(sync = false)
            writeU16Le(0x0043)
            writeStr8(exchangeName)
            writeStr8("topic")
            writeFramingFieldTable(emptyMap())
        }.toByteArray()

        private fun encodeMessageHeader(contentLength: Int): ByteArray = BigEndianWriter().apply {
            val deliveryBody = BigEndianWriter().apply {
                writeU16(0x2002)
                writeU64(RemoteKeyboardProtocol.MESSAGE_TTL_MILLISECONDS)
                writeStr8("")
            }.toByteArray()
            writeU32((2 + deliveryBody.size).toLong())
            writeU16(0x0401)
            writeBytes(deliveryBody)

            val messageBody = BigEndianWriter().apply {
                writeU16(0x1100)
                writeU64(contentLength.toLong())
                writeStr8("amqp/map")
            }.toByteArray()
            writeU32((2 + messageBody.size).toLong())
            writeU16(0x0403)
            writeBytes(messageBody)
        }.toByteArray()

        private fun encodeApplicationMap(values: Map<String, Any>): ByteArray {
            val inner = BigEndianWriter()
            values.forEach { (key, value) ->
                inner.writeShortString(key)
                when (value) {
                    is Boolean -> {
                        inner.writeU8(0x08)
                        inner.writeU8(if (value) 1 else 0)
                    }
                    is Int -> {
                        inner.writeU8(0x21)
                        inner.writeI32(value)
                    }
                    is Long -> {
                        inner.writeU8(0x32)
                        inner.writeU64(value)
                    }
                    is String -> {
                        inner.writeU8(0x95)
                        inner.writeU16(value.toByteArray(StandardCharsets.UTF_8).size)
                        inner.writeUtf8(value)
                    }
                    else -> error("Unsupported application map value type: ${value::class.java.name}")
                }
            }
            val body = inner.toByteArray()
            return BigEndianWriter().apply {
                writeU32((4 + body.size).toLong())
                writeU32(values.size.toLong())
                writeBytes(body)
            }.toByteArray()
        }

        private fun decodeConnectionStartMechanisms(payload: ByteArray): List<String> {
            var offset = 2
            val fieldTableLength = readU32(payload, offset).toInt()
            offset += 4 + fieldTableLength
            val length = readU32(payload, offset).toInt()
            if (length == 0) return emptyList()
            offset += 4

            val type = payload[offset].toInt() and 0xFF
            if (type == 0x95) {
                offset += 1
                val count = readU32(payload, offset).toInt()
                offset += 4
                return buildList {
                    repeat(count) {
                        val size = readU16(payload, offset)
                        offset += 2
                        add(String(payload, offset, size, StandardCharsets.UTF_8))
                        offset += size
                    }
                }
            }

            return decodeMechanismString(payload, offset, length)
        }

        private fun decodeMechanismString(payload: ByteArray, offset: Int, length: Int): List<String> {
            val body = String(payload, offset, length, StandardCharsets.UTF_8)
                .trim()
                .trim('\u0000')
            if (body.isBlank()) {
                return emptyList()
            }

            return body
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        private fun decodeConnectionTune(payload: ByteArray): TuneValues {
            var offset = 2
            val channelMax = readU16(payload, offset)
            offset += 2
            val maxFrameSize = readU16(payload, offset)
            offset += 2
            val heartbeatMin = readU16(payload, offset)
            offset += 2
            val heartbeatMax = readU16(payload, offset)
            return TuneValues(channelMax, maxFrameSize, heartbeatMin, heartbeatMax)
        }

        private fun decodeExecutionException(payload: ByteArray): String {
            var offset = 0
            val headerSize = payload[offset].toInt() and 0xFF
            offset += 1 + headerSize + 1
            val errorCode = readU16(payload, offset)
            offset += 2
            val commandId = readU32(payload, offset)
            offset += 4
            val classCode = payload[offset++].toInt() and 0xFF
            val commandCode = payload[offset++].toInt() and 0xFF
            val fieldIndex = payload[offset++].toInt() and 0xFF
            val size = readU16(payload, offset)
            offset += 2
            val description = String(payload, offset, size, StandardCharsets.UTF_8)
            return "Broker returned execution.exception errorCode=$errorCode commandId=$commandId class=$classCode command=$commandCode field=$fieldIndex: $description"
        }

        private fun describeIncomingFrame(frame: QpidFrame): String? {
            if (frame.type == SEGMENT_TYPE_CONTROL && frame.channel == SESSION_CHANNEL && frame.classId == 2 && frame.methodId == 7) {
                return "qpidd: session.command-point"
            }
            if (frame.type == SEGMENT_TYPE_CONTROL && frame.channel == SESSION_CHANNEL && frame.classId == 2 && frame.methodId == 4) {
                return "qpidd: session.detached"
            }
            if (frame.type == SEGMENT_TYPE_CONTROL && frame.channel == 0 && frame.classId == 1 && frame.methodId == 0x0C) {
                return "qpidd: connection.close-ok"
            }
            if (frame.type == SEGMENT_TYPE_COMMAND && frame.channel == SESSION_CHANNEL && frame.classId == 3 && frame.methodId == 3) {
                return "qpidd: " + decodeExecutionException(frame.payload)
            }
            return null
        }

        private fun selectMechanism(mechanisms: List<String>): String = when {
            "ANONYMOUS" in mechanisms -> "ANONYMOUS"
            "PLAIN" in mechanisms -> "PLAIN"
            else -> error("Broker did not offer ANONYMOUS or PLAIN SASL.")
        }

        private data class QpidFrame(val type: Int, val channel: Int, val classId: Int?, val methodId: Int?, val payload: ByteArray)
        private data class TuneValues(val channelMax: Int, val maxFrameSize: Int, val heartbeatMin: Int, val heartbeatMax: Int)

        private class BigEndianWriter {
            private val stream = ByteArrayOutputStream()

            fun writeU8(value: Int) = stream.write(value)
            fun writeModelMethodHeader(sync: Boolean) {
                writeU8(1)
                writeU8(if (sync) 1 else 0)
            }
            fun writeU16(value: Int) = stream.write(ByteBuffer.allocate(2).putShort(value.toShort()).array())
            fun writeU16Le(value: Int) = stream.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
            fun writeI32(value: Int) = stream.write(ByteBuffer.allocate(4).putInt(value).array())
            fun writeU32(value: Long) = stream.write(ByteBuffer.allocate(4).putInt(value.toInt()).array())
            fun writeU64(value: Long) = stream.write(ByteBuffer.allocate(8).putLong(value).array())
            fun writeStr8(value: String) {
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= 255) { "str8 value is too long." }
                writeU8(bytes.size)
                writeBytes(bytes)
            }
            fun writeStr16Array(values: List<String>) {
                val inner = BigEndianWriter()
                inner.writeU8(0x95)
                inner.writeU32(values.size.toLong())
                values.forEach {
                    inner.writeU16(it.toByteArray(StandardCharsets.UTF_8).size)
                    inner.writeUtf8(it)
                }
                val payload = inner.toByteArray()
                writeU32(payload.size.toLong())
                writeBytes(payload)
            }
            fun writeVbin16(bytes: ByteArray) {
                writeU16(bytes.size)
                writeBytes(bytes)
            }
            fun writeVbin32(bytes: ByteArray) {
                writeU32(bytes.size.toLong())
                writeBytes(bytes)
            }
            fun writeFieldTable(values: Map<String, Any>) {
                val inner = BigEndianWriter()
                inner.writeU32(values.size.toLong())
                values.forEach { (key, value) ->
                    inner.writeShortString(key)
                    when (value) {
                        is Int -> {
                            inner.writeU8(0x21)
                            inner.writeI32(value)
                        }
                        is String -> {
                            inner.writeU8(0x95)
                            inner.writeU16(value.toByteArray(StandardCharsets.UTF_8).size)
                            inner.writeUtf8(value)
                        }
                        else -> error("Unsupported field-table type: ${value::class.java.name}")
                    }
                }
                val body = inner.toByteArray()
                writeU32(body.size.toLong())
                writeBytes(body)
            }
            fun writeFramingFieldTable(values: Map<String, Any>) {
                val inner = BigEndianWriter()
                inner.writeU32(values.size.toLong())
                values.forEach { (key, value) ->
                    inner.writeShortString(key)
                    when (value) {
                        is Boolean -> {
                            inner.writeU8(0x08)
                            inner.writeU8(if (value) 1 else 0)
                        }
                        is Int -> {
                            inner.writeU8(0x21)
                            inner.writeI32(value)
                        }
                        is Long -> {
                            inner.writeU8(0x32)
                            inner.writeU64(value)
                        }
                        is String -> {
                            inner.writeU8(0x95)
                            inner.writeU16(value.toByteArray(StandardCharsets.UTF_8).size)
                            inner.writeUtf8(value)
                        }
                        else -> error("Unsupported framing field-table type: ${value::class.java.name}")
                    }
                }
                val body = inner.toByteArray()
                writeU32(body.size.toLong())
                writeBytes(body)
            }
            fun writeShortString(value: String) {
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= 255) { "short string key is too long." }
                writeU8(bytes.size)
                writeBytes(bytes)
            }
            fun writeUtf8(value: String) = writeBytes(value.toByteArray(StandardCharsets.UTF_8))
            fun writeBytes(bytes: ByteArray) = stream.write(bytes)
            fun toByteArray(): ByteArray = stream.toByteArray()
        }

        companion object {
            private const val SEGMENT_TYPE_CONTROL = 0
            private const val SEGMENT_TYPE_COMMAND = 1
            private const val SEGMENT_TYPE_HEADER = 2
            private const val SEGMENT_TYPE_BODY = 3
            private const val SEGMENT_TYPE_HEARTBEAT = 4
            private const val SESSION_CHANNEL = 1

            private fun composeFlags(firstSegment: Boolean, lastSegment: Boolean, firstFrame: Boolean, lastFrame: Boolean): Int {
                var flags = 0
                if (firstSegment) flags = flags or 0x08
                if (lastSegment) flags = flags or 0x04
                if (firstFrame) flags = flags or 0x02
                if (lastFrame) flags = flags or 0x01
                return flags
            }

            private fun parseBrokerEndpoint(brokerUri: String): Pair<String, Int> {
                val prefix = "amqp:tcp:"
                require(brokerUri.startsWith(prefix, true)) { "Unsupported broker URI: $brokerUri" }
                val endpoint = brokerUri.substring(prefix.length)
                val separator = endpoint.lastIndexOf(':')
                require(separator > 0 && separator < endpoint.length - 1) { "Unsupported broker URI: $brokerUri" }
                return endpoint.substring(0, separator) to endpoint.substring(separator + 1).toInt()
            }

            private fun readU16(bytes: ByteArray, offset: Int): Int =
                ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

            private fun readU32(bytes: ByteArray, offset: Int): Long =
                ByteBuffer.wrap(bytes, offset, 4).int.toLong() and 0xFFFFFFFFL

            private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
                bytes[offset] = ((value shr 8) and 0xFF).toByte()
                bytes[offset + 1] = (value and 0xFF).toByte()
            }
        }
    }
}
