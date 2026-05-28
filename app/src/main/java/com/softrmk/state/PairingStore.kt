package com.softrmk.state

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PairingState(
    val pairedTargets: List<String> = emptyList(),
    val lastActiveTargetId: String? = null,
    val senderMacAddress: String? = null,
    val knownBrokersById: Map<String, String> = emptyMap(),
    val knownTargetNamesById: Map<String, String> = emptyMap(),
    val hasSeenStartupNotice: Boolean = false,
)

class PairingStore(context: Context) {
    private val stateFile = File(context.filesDir, "pairing-state.json")

    fun load(): PairingState {
        if (!stateFile.exists()) {
            return PairingState()
        }
        return runCatching {
            val json = JSONObject(stateFile.readText())
            PairingState(
                pairedTargets = json.optJSONArray("pairedTargets").toStringList(),
                lastActiveTargetId = json.optString("lastActiveTargetId").ifBlank { null },
                senderMacAddress = json.optString("senderMacAddress").ifBlank { null }?.let(::normalizeId),
                knownBrokersById = json.optJSONObject("knownBrokersById").toStringMap(),
                knownTargetNamesById = json.optJSONObject("knownTargetNamesById").toStringMap(),
                hasSeenStartupNotice = json.optBoolean("hasSeenStartupNotice", false),
            )
        }.getOrDefault(PairingState()).normalize()
    }

    fun setActiveTarget(targetId: String): PairingState {
        val state = load()
        val normalized = normalizeId(targetId)
        return save(
            state.copy(
                pairedTargets = (state.pairedTargets + normalized).distinctBy { it.uppercase() },
                lastActiveTargetId = normalized
            )
        )
    }

    fun removePairedTarget(targetId: String): PairingState {
        val normalized = normalizeId(targetId)
        val state = load()
        return save(
            state.copy(
                pairedTargets = state.pairedTargets.filterNot { it.equals(normalized, true) },
                lastActiveTargetId = state.lastActiveTargetId?.takeUnless { it.equals(normalized, true) },
                knownBrokersById = state.knownBrokersById.filterKeys { !it.equals(normalized, true) },
                knownTargetNamesById = state.knownTargetNamesById.filterKeys { !it.equals(normalized, true) }
            )
        )
    }

    fun setSenderMacAddress(macAddress: String): PairingState = save(load().copy(senderMacAddress = normalizeId(macAddress)))

    fun upsertKnownBroker(targetId: String, brokerUri: String): PairingState {
        val state = load()
        return save(state.copy(knownBrokersById = state.knownBrokersById + (normalizeId(targetId) to brokerUri.trim())))
    }

    fun upsertKnownTargetName(targetId: String, name: String): PairingState {
        val state = load()
        return save(state.copy(knownTargetNamesById = state.knownTargetNamesById + (normalizeId(targetId) to name.trim())))
    }

    fun setHasSeenStartupNotice(seen: Boolean): PairingState = save(load().copy(hasSeenStartupNotice = seen))

    private fun save(state: PairingState): PairingState {
        val normalized = state.normalize()
        val json = JSONObject()
            .put("pairedTargets", JSONArray(normalized.pairedTargets))
            .put("lastActiveTargetId", normalized.lastActiveTargetId)
            .put("senderMacAddress", normalized.senderMacAddress)
            .put("knownBrokersById", JSONObject(normalized.knownBrokersById))
            .put("knownTargetNamesById", JSONObject(normalized.knownTargetNamesById))
            .put("hasSeenStartupNotice", normalized.hasSeenStartupNotice)
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(json.toString(2))
        return normalized
    }

    private fun PairingState.normalize(): PairingState {
        val paired = pairedTargets.map(::normalizeId).distinctBy { it.uppercase() }
        return copy(
            pairedTargets = paired.sortedWith(String.CASE_INSENSITIVE_ORDER),
            lastActiveTargetId = lastActiveTargetId?.let(::normalizeId),
            knownBrokersById = knownBrokersById.mapKeys { normalizeId(it.key) },
            knownTargetNamesById = knownTargetNamesById.mapKeys { normalizeId(it.key) },
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val value = optString(index)
                if (value.isNotBlank()) add(normalizeId(value))
            }
        }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val map = linkedMapOf<String, String>()
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val value = optString(key)
            if (key.isNotBlank() && value.isNotBlank()) {
                map[normalizeId(key)] = value.trim()
            }
        }
        return map
    }

    companion object {
        fun normalizeId(value: String): String = value.trim().uppercase()
    }
}
