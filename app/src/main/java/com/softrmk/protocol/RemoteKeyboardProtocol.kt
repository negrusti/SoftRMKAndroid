package com.softrmk.protocol

enum class OperatingMode(val value: Int) { Invalid(0), Normal(1), Pairing(2) }
enum class KeyEventType(val value: Int) { Invalid(0), Press(1), Repeat(2), Release(3) }

object RemoteKeyboardProtocol {
    const val REMOTE_KEYBOARD_SYSTEM_FUNCTION = "RemoteKeyboard"
    const val OBSERVED_KEYBOARD_ADDRESS = "atom.remote.keyboard "
    const val HEARTBEAT_OPTION_VALUE = 1
    const val MESSAGE_TTL_MILLISECONDS = 5_000L

    const val FIELD_SYSTEM_FUNCTION = "system_function"
    const val FIELD_NAME = "name"
    const val FIELD_MAC_ADDRESS = "mac_address"
    const val FIELD_OPERATING_MODE = "operating_mode"
    const val FIELD_KEY_EVENT = "key_event"
    const val FIELD_KEY_CODE = "key_code"
    const val FIELD_ROTARY_DELTA = "rotary_delta"
    const val FIELD_PAIRING_STATE = "pairing_state"

    const val MESSAGE_KEY = "KeyMessage"
    const val MESSAGE_ROTARY = "RotaryMessage"
    const val MESSAGE_PAIRING_CONFIRMATION_RESPONSE = "PairingConfirmationResponseMessage"
}

class RemoteKeyboardEnvelope(val messageName: String) {
    private val fields = linkedMapOf<String, Any>()

    fun add(key: String, value: Any): RemoteKeyboardEnvelope {
        fields[key] = value
        return this
    }

    fun toWireMap(): Map<String, Any> = linkedMapOf(
        RemoteKeyboardProtocol.FIELD_SYSTEM_FUNCTION to RemoteKeyboardProtocol.REMOTE_KEYBOARD_SYSTEM_FUNCTION,
        RemoteKeyboardProtocol.FIELD_NAME to messageName,
    ) + fields
}

object RemoteKeyboardMessages {
    fun key(macAddress: String, keyEventType: KeyEventType, keyCode: Int, mode: OperatingMode) =
        RemoteKeyboardEnvelope(RemoteKeyboardProtocol.MESSAGE_KEY)
            .add(RemoteKeyboardProtocol.FIELD_MAC_ADDRESS, macAddress)
            .add(RemoteKeyboardProtocol.FIELD_KEY_EVENT, keyEventType.value)
            .add(RemoteKeyboardProtocol.FIELD_KEY_CODE, keyCode)
            .add(RemoteKeyboardProtocol.FIELD_OPERATING_MODE, mode.value)

    fun rotary(macAddress: String, delta: Int, mode: OperatingMode) =
        RemoteKeyboardEnvelope(RemoteKeyboardProtocol.MESSAGE_ROTARY)
            .add(RemoteKeyboardProtocol.FIELD_MAC_ADDRESS, macAddress)
            .add(RemoteKeyboardProtocol.FIELD_ROTARY_DELTA, delta)
            .add(RemoteKeyboardProtocol.FIELD_OPERATING_MODE, mode.value)

    fun pairingConfirmationResponse(macAddress: String, paired: Boolean) =
        RemoteKeyboardEnvelope(RemoteKeyboardProtocol.MESSAGE_PAIRING_CONFIRMATION_RESPONSE)
            .add(RemoteKeyboardProtocol.FIELD_MAC_ADDRESS, macAddress)
            .add(RemoteKeyboardProtocol.FIELD_PAIRING_STATE, paired)
}

object Rmk10Keys {
    private val aliases = mapOf(
        "home" to 16777270,
        "waypoint" to 16777271,
        "mob" to 16777271,
        "menu" to 16777272,
        "window" to 16777274,
        "switch" to 16777274,
        "pilot" to 16777275,
        "stby" to 16777275,
        "standby" to 16777275,
        "auto" to 16777275,
        "back" to 16777216,
        "ok" to 16777220,
        "enter" to 16777220,
        "power" to 16777224,
        "brightness" to 16777224,
        "zoom-out" to 16777238,
        "zoom-in" to 16777239,
        "left" to 16777234,
        "up" to 16777235,
        "right" to 16777236,
        "down" to 16777237,
    )

    fun parse(value: String): Int {
        value.toIntOrNull()?.let { return it }
        return aliases[value.trim().lowercase()] ?: error("Unknown key '$value'.")
    }
}
