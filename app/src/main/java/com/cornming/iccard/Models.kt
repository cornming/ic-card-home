package com.cornming.iccard

/** What a card does to a device when it is tapped. */
enum class CardAction(val label: String) {
    ON("開"),
    OFF("關"),
    TOGGLE("切換"),
}

/** A device as shown in the UI. Only devices with [hasOnOff] can be mapped to a card. */
data class DeviceRow(
    val id: String,
    val name: String,
    val typeNames: List<String>,
    val online: Boolean,
    val hasOnOff: Boolean,
    val isOn: Boolean?,
    /** Name or type hints that this may be a door, gate or lock. Such devices are blocked by default. */
    val looksLikeDoor: Boolean,
)

/** One registered card: UID of the card -> action on a device. */
data class CardMapping(
    val uid: String,
    val deviceId: String,
    val deviceName: String,
    val action: CardAction,
    val looksLikeDoor: Boolean,
)

/** Result of looking at a card UID, used for the hint under the "last scanned" line. */
fun uidHint(uid: String): String? {
    // A 4-byte UID starting with 0x08 is the ISO 14443-3 "random UID" form: it changes every tap.
    if (uid.length == 8 && uid.startsWith("08")) {
        return "這個 ID 開頭是 08，通常表示每次刷都會變的隨機 ID，無法穩定辨識這張卡。"
    }
    return null
}
