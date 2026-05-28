package com.softrmk.discovery

import com.softrmk.state.PairingStore

data class BrokerResolutionResult(
    val targetId: String,
    val brokerUri: String?,
    val source: String,
    val matchedBroker: DiscoveredBroker? = null,
    val discoveredBrokers: List<DiscoveredBroker> = emptyList(),
)

class BrokerResolver(
    private val store: PairingStore,
    private val discovery: ServiceDiscoveryMdns,
) {
    suspend fun resolveForTarget(targetId: String): BrokerResolutionResult {
        val normalized = PairingStore.normalizeId(targetId)
        val state = store.load()
        state.knownBrokersById[normalized]?.takeIf { it.isNotBlank() }?.let { brokerUri ->
            return BrokerResolutionResult(normalized, brokerUri, "state-cache")
        }

        val brokers = discovery.discover(timeoutMillis = 3_000L)
        val matched = brokers.firstOrNull { it.targetId.equals(normalized, true) }
        if (matched != null) {
            store.upsertKnownBroker(normalized, matched.brokerUrl)
            return BrokerResolutionResult(normalized, matched.brokerUrl, "mdns", matched, brokers)
        }

        return BrokerResolutionResult(normalized, null, "unresolved", discoveredBrokers = brokers)
    }
}
