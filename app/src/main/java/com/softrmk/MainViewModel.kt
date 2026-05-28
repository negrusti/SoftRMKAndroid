package com.softrmk

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.softrmk.discovery.BrokerResolver
import com.softrmk.discovery.DiscoveredBroker
import com.softrmk.discovery.ServiceDiscoveryMdns
import com.softrmk.protocol.KeyEventType
import com.softrmk.protocol.OperatingMode
import com.softrmk.protocol.RemoteKeyboardClient
import com.softrmk.protocol.RemoteKeyboardEnvelope
import com.softrmk.protocol.RemoteKeyboardMessages
import com.softrmk.protocol.Rmk10Keys
import com.softrmk.state.PairingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale

data class PairingDialogState(
    val visible: Boolean = false,
    val discovering: Boolean = false,
    val submitting: Boolean = false,
    val brokers: List<DiscoveredBroker> = emptyList(),
    val selectedTargetId: String? = null,
    val statusText: String = "",
)

data class MainUiState(
    val selectedMfdText: String = "",
    val statusLines: List<String> = emptyList(),
    val busy: Boolean = false,
    val pairingDialog: PairingDialogState = PairingDialogState(),
    val startupNoticeVisible: Boolean = false,
)

class MainViewModel(private val appContext: Context) : ViewModel() {
    private companion object {
        const val MAX_STATUS_LINES = 250
        val REPEATED_LOG_PATTERN = Regex("""^(.*) \((\d+)\)$""")
    }

    private val store = PairingStore(appContext)
    private val discovery = ServiceDiscoveryMdns(appContext)
    private val brokerResolver = BrokerResolver(store, discovery)
    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedMfdText = appContext.getString(R.string.selected_mfd_not_paired),
            statusLines = listOf(appContext.getString(R.string.status_ready)),
            pairingDialog = PairingDialogState(
                statusText = appContext.getString(R.string.pairing_status_initial)
            )
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var activeBroker: DiscoveredBroker? = null
    private var activeTargetId: String? = null
    private var activeTargetName: String? = null
    private var activeSenderMac: String? = null
    private var activeBrokerUri: String? = null
    private var holdJob: Job? = null
    private var heldKeyCode: Int = 0
    private var heldKeyName: String? = null
    private var heldBrokerUri: String? = null
    private var heldSenderMac: String? = null
    private val sessionMutex = Mutex()
    private var liveSession: BrokerSession? = null

    init {
        val state = loadStateDefaults()
        if (!state.hasSeenStartupNotice) {
            _uiState.update { it.copy(startupNoticeVisible = true) }
        } else if (state.pairedTargets.isEmpty()) {
            openPairingDialog()
        }
    }

    fun dismissStartupNotice() {
        store.setHasSeenStartupNotice(true)
        val shouldOpenPairing = store.load().pairedTargets.isEmpty()
        _uiState.update { it.copy(startupNoticeVisible = false) }
        if (shouldOpenPairing) {
            openPairingDialog()
        }
    }

    fun openPairingDialog() {
        _uiState.update {
            it.copy(pairingDialog = it.pairingDialog.copy(visible = true))
        }
        refreshDiscovery()
    }

    fun dismissPairingDialog() {
        _uiState.update {
            it.copy(pairingDialog = it.pairingDialog.copy(visible = false, discovering = false, submitting = false))
        }
    }

    fun refreshDiscovery() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    pairingDialog = it.pairingDialog.copy(
                        visible = true,
                        discovering = true,
                        submitting = false,
                        brokers = emptyList(),
                        selectedTargetId = null,
                        statusText = appContext.getString(R.string.pairing_status_discovering)
                    )
                )
            }
            logStatus(appContext.getString(R.string.pairing_status_discovering))
            runCatching { discovery.discover(timeoutMillis = 3_000L) }
                .onSuccess { brokers ->
                    brokers.forEach { broker ->
                        store.upsertKnownBroker(broker.targetId, broker.brokerUrl)
                        store.upsertKnownTargetName(broker.targetId, broker.displayName)
                        logStatus("Found ${broker.displayName} at ${broker.brokerUrl} on ${broker.interfaceName}.")
                    }
                    _uiState.update {
                        it.copy(
                            pairingDialog = it.pairingDialog.copy(
                                discovering = false,
                                submitting = false,
                                brokers = brokers,
                                selectedTargetId = brokers.firstOrNull()?.targetId,
                                statusText = if (brokers.isEmpty()) {
                                    appContext.getString(R.string.pairing_status_none_found)
                                } else {
                                    ""
                                }
                            )
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            pairingDialog = it.pairingDialog.copy(
                                discovering = false,
                                submitting = false,
                                statusText = error.message ?: appContext.getString(R.string.pairing_status_discovery_failed)
                            )
                        )
                    }
                    logStatus(error.message ?: appContext.getString(R.string.pairing_status_discovery_failed))
                }
        }
    }

    fun selectBroker(targetId: String) {
        _uiState.update {
            it.copy(pairingDialog = it.pairingDialog.copy(selectedTargetId = targetId))
        }
    }

    fun confirmPairing() {
        val selectedId = uiState.value.pairingDialog.selectedTargetId ?: return
        val broker = uiState.value.pairingDialog.brokers.firstOrNull { it.targetId == selectedId } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    pairingDialog = it.pairingDialog.copy(
                        submitting = true,
                        statusText = "Pairing with ${broker.displayName} via ${broker.interfaceName}..."
                    )
                )
            }
            runCatching {
                pairWithBroker(broker, paired = true)
                _uiState.update {
                    it.copy(pairingDialog = it.pairingDialog.copy(visible = false, submitting = false))
                }
                logStatus("Paired with ${broker.displayName} via ${broker.interfaceName}.")
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        pairingDialog = it.pairingDialog.copy(
                            submitting = false,
                            statusText = error.message ?: "Pairing failed."
                        )
                    )
                }
                logStatus(error.message ?: "Pairing failed.")
            }
        }
    }

    fun tapKey(keyName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runBusyAction {
                sendTap(keyName)
                logStatus("Sent $keyName tap.")
            }
        }
    }

    fun startHold(keyName: String) {
        if (holdJob != null) {
            return
        }
        holdJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                heldSenderMac = resolveSenderMac()
                heldBrokerUri = resolveBrokerUri()
                heldKeyName = keyName
                heldKeyCode = Rmk10Keys.parse(keyName)
                sendKeyEvent(heldBrokerUri!!, heldSenderMac!!, KeyEventType.Press, heldKeyCode)
                logStatus("Holding $keyName: press sent.")
                delay(320)
                var repeatCount = 0
                while (true) {
                    sendKeyEvent(heldBrokerUri!!, heldSenderMac!!, KeyEventType.Repeat, heldKeyCode)
                    repeatCount += 1
                    logStatus("Holding $keyName: repeat $repeatCount.")
                    delay(120)
                }
            } catch (_: CancellationException) {
            } catch (error: Throwable) {
                clearHoldState()
                logStatus(error.message ?: "Hold failed.")
            }
        }
    }

    fun stopHold() {
        val brokerUri = heldBrokerUri
        val senderMac = heldSenderMac
        val keyCode = heldKeyCode
        val keyName = heldKeyName
        holdJob?.cancel()
        holdJob = null
        clearHoldState()
        if (brokerUri != null && senderMac != null && keyCode != 0) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    sendKeyEvent(brokerUri, senderMac, KeyEventType.Release, keyCode)
                    logStatus("Released ${keyName ?: "key"}.")
                }.onFailure { error ->
                    logStatus(error.message ?: "Release failed.")
                }
            }
        }
    }

    fun sendRotary(delta: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            runBusyAction {
                val envelope = RemoteKeyboardMessages.rotary(resolveSenderMac(), delta, OperatingMode.Normal)
                sendEnvelope(envelope)
                logStatus("Sent rotary ${if (delta > 0) "+$delta" else delta}.")
            }
        }
    }

    private fun loadStateDefaults(): com.softrmk.state.PairingState {
        val state = store.load()
        activeTargetId = state.lastActiveTargetId
        activeTargetName = state.lastActiveTargetId?.let(state.knownTargetNamesById::get)
        activeBrokerUri = state.lastActiveTargetId?.let(state.knownBrokersById::get)
        activeSenderMac = state.senderMacAddress
        updateSelectedMfdText()
        return state
    }

    private suspend fun pairWithBroker(broker: DiscoveredBroker, paired: Boolean) {
        val senderMac = broker.interfaceMacAddress ?: resolveSenderMacFallback()
        val envelope = RemoteKeyboardMessages.pairingConfirmationResponse(senderMac, paired)
        sendThroughSession(broker.brokerUrl, envelope)

        activeBroker = broker
        activeTargetId = broker.targetId
        activeTargetName = broker.displayName
        activeSenderMac = senderMac
        activeBrokerUri = broker.brokerUrl

        if (paired) {
            store.upsertKnownBroker(broker.targetId, broker.brokerUrl)
            store.upsertKnownTargetName(broker.targetId, broker.displayName)
            store.setActiveTarget(broker.targetId)
            store.setSenderMacAddress(senderMac)
        } else {
            store.removePairedTarget(broker.targetId)
        }

        updateSelectedMfdText()
    }

    private suspend fun sendTap(keyName: String) {
        val senderMac = resolveSenderMac()
        val brokerUri = resolveBrokerUri()
        val keyCode = Rmk10Keys.parse(keyName)
        sendKeyEvent(brokerUri, senderMac, KeyEventType.Press, keyCode)
        delay(50)
        sendKeyEvent(brokerUri, senderMac, KeyEventType.Release, keyCode)
    }

    private suspend fun sendKeyEvent(brokerUri: String, senderMac: String, keyEvent: KeyEventType, keyCode: Int) {
        val envelope = RemoteKeyboardMessages.key(senderMac, keyEvent, keyCode, OperatingMode.Normal)
        sendThroughSession(brokerUri, envelope)
    }

    private suspend fun sendEnvelope(envelope: RemoteKeyboardEnvelope) {
        val brokerUri = resolveBrokerUri()
        sendThroughSession(brokerUri, envelope)
    }

    private suspend fun resolveBrokerUri(): String {
        activeBrokerUri?.takeIf { it.isNotBlank() }?.let { return it }
        val targetId = activeTargetId ?: error(appContext.getString(R.string.pairing_status_no_selected))
        val resolution = brokerResolver.resolveForTarget(targetId)
        activeBroker = resolution.matchedBroker
        activeBrokerUri = resolution.brokerUri ?: error("No broker resolved for target $targetId. Tap Pair first.")
        if (activeSenderMac.isNullOrBlank()) {
            resolution.matchedBroker?.interfaceMacAddress?.let {
                activeSenderMac = it
                store.setSenderMacAddress(it)
            }
        }
        updateSelectedMfdText()
        return activeBrokerUri!!
    }

    private suspend fun resolveSenderMac(): String {
        activeSenderMac?.takeIf { it.isNotBlank() }?.let { return it }
        val resolution = activeBroker ?: brokerResolver.resolveForTarget(
            activeTargetId ?: error(appContext.getString(R.string.pairing_status_no_selected))
        ).matchedBroker
        val inferred = resolution?.interfaceMacAddress ?: resolveSenderMacFallback()
        if (inferred.isBlank()) {
            error(appContext.getString(R.string.pairing_status_sender_mac_missing))
        }
        activeSenderMac = inferred
        store.setSenderMacAddress(inferred)
        updateSelectedMfdText()
        return inferred
    }

    private fun resolveSenderMacFallback(): String {
        discovery.defaultInterfaces()
            .firstNotNullOfOrNull { mac ->
                mac.macAddress?.takeIf(::isUsableMacAddress)
            }
            ?.let { return PairingStore.normalizeId(it) }

        activeSenderMac?.takeIf(::isUsableMacAddress)?.let { return it }

        store.load().senderMacAddress?.takeIf(::isUsableMacAddress)?.let { return it }

        val synthetic = buildSyntheticSenderMac()
        logStatus("Wi-Fi MAC unavailable on this Android device. Using stable synthetic sender MAC $synthetic.")
        return synthetic
    }

    private fun buildSyntheticSenderMac(): String {
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
            ?: "rmk10-android"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray())
            .copyOfRange(0, 6)
        digest[0] = ((digest[0].toInt() and 0xFE) or 0x02).toByte()
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private fun isUsableMacAddress(value: String?): Boolean {
        if (value.isNullOrBlank()) {
            return false
        }

        val normalized = PairingStore.normalizeId(value)
        return normalized != "02:00:00:00:00:00" && normalized != "00:00:00:00:00:00"
    }

    private fun updateSelectedMfdText() {
        val text = when {
            activeBroker != null -> activeBroker!!.displayName
            !activeTargetName.isNullOrBlank() -> activeTargetName!!
            else -> ""
        }
        _uiState.update { it.copy(selectedMfdText = text) }
    }

    private suspend fun runBusyAction(block: suspend () -> Unit) {
        if (_uiState.value.busy) {
            return
        }
        _uiState.update { it.copy(busy = true) }
        try {
            block()
        } catch (error: Throwable) {
            logStatus(error.message ?: "Operation failed.")
        } finally {
            _uiState.update { it.copy(busy = false) }
        }
    }

    private fun clearHoldState() {
        heldKeyCode = 0
        heldKeyName = null
        heldBrokerUri = null
        heldSenderMac = null
    }

    private fun logStatus(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _uiState.update {
            it.copy(statusLines = appendStatusLine(it.statusLines, timestamp, message).takeLast(MAX_STATUS_LINES))
        }
    }

    private fun appendStatusLine(lines: List<String>, timestamp: String, message: String): List<String> {
        val lastLine = lines.lastOrNull() ?: return lines + "$timestamp  $message"
        val separatorIndex = lastLine.indexOf("  ")
        if (separatorIndex < 0) {
            return lines + "$timestamp  $message"
        }

        val lastMessage = lastLine.substring(separatorIndex + 2)
        val match = REPEATED_LOG_PATTERN.matchEntire(lastMessage)
        val normalizedLastMessage = match?.groupValues?.get(1) ?: lastMessage
        val repeatCount = match?.groupValues?.get(2)?.toIntOrNull() ?: 1

        if (normalizedLastMessage != message) {
            return lines + "$timestamp  $message"
        }

        return lines.dropLast(1) + "$timestamp  $message (${repeatCount + 1})"
    }

    private fun logBrokerEvent(message: String) {
        logStatus(message)
    }

    private fun handleBrokerDisconnected(brokerUri: String) {
        viewModelScope.launch {
            sessionMutex.withLock {
                if (liveSession?.brokerUri == brokerUri) {
                    liveSession = null
                }
            }
        }
    }

    override fun onCleared() {
        liveSession?.close()
        liveSession = null
        super.onCleared()
    }

    private suspend fun sendThroughSession(brokerUri: String, envelope: RemoteKeyboardEnvelope) {
        sessionMutex.withLock {
            val activeSession = ensureSessionLocked(brokerUri)
            try {
                activeSession.client.send(envelope)
            } catch (firstError: Throwable) {
                activeSession.close()
                liveSession = null

                val retrySession = ensureSessionLocked(brokerUri)
                try {
                    retrySession.client.send(envelope)
                } catch (retryError: Throwable) {
                    retrySession.close()
                    liveSession = null
                    throw retryError
                }
            }
        }
    }

    private fun ensureSessionLocked(brokerUri: String): BrokerSession {
        val existing = liveSession
        if (existing != null && existing.brokerUri == brokerUri) {
            return existing
        }

        existing?.close()
        val client = RemoteKeyboardClient(
            brokerUri,
            onBrokerEvent = ::logBrokerEvent,
            onConnectionClosed = { handleBrokerDisconnected(brokerUri) }
        )
        client.connect()
        return BrokerSession(brokerUri, client).also { liveSession = it }
    }

    private data class BrokerSession(
        val brokerUri: String,
        val client: RemoteKeyboardClient,
    ) {
        fun close() {
            client.close()
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(context.applicationContext) as T
        }
    }
}
