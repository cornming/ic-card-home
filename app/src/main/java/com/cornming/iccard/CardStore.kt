package com.cornming.iccard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists the card mappings and the safety switches in app-private storage. */
class CardStore(context: Context) {
    private val prefs = context.getSharedPreferences("iccard", Context.MODE_PRIVATE)

    /** When true the app only logs what it would do. Default true so nothing moves by accident. */
    var dryRun: Boolean
        get() = prefs.getBoolean(KEY_DRY_RUN, true)
        set(value) = prefs.edit().putBoolean(KEY_DRY_RUN, value).apply()

    /** When false (default) mappings to devices that look like doors or locks are never executed. */
    var allowDoorDevices: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_DOORS, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_DOORS, value).apply()

    /** When true (default) a tap on a card mapped to a door-like device asks for confirmation first. */
    var confirmDoors: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_DOORS, true)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_DOORS, value).apply()

    /** Epoch millis of the last automatic update check, so the app does not ask GitHub on every launch. */
    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    fun load(): List<CardMapping> {
        val raw = prefs.getString(KEY_MAPPINGS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val door = o.optBoolean("looksLikeDoor", false)
                CardMapping(
                    uid = o.getString("uid"),
                    deviceId = o.getString("deviceId"),
                    deviceName = o.getString("deviceName"),
                    // Door relays are inching: only ON makes sense, even for mappings saved by an older version.
                    action = if (door) CardAction.ON else CardAction.valueOf(o.getString("action")),
                    looksLikeDoor = door,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(mappings: List<CardMapping>) {
        val array = JSONArray()
        mappings.forEach { m ->
            array.put(
                JSONObject()
                    .put("uid", m.uid)
                    .put("deviceId", m.deviceId)
                    .put("deviceName", m.deviceName)
                    .put("action", m.action.name)
                    .put("looksLikeDoor", m.looksLikeDoor)
            )
        }
        prefs.edit().putString(KEY_MAPPINGS, array.toString()).apply()
    }

    private companion object {
        const val KEY_MAPPINGS = "mappings"
        const val KEY_DRY_RUN = "dryRun"
        const val KEY_ALLOW_DOORS = "allowDoorDevices"
        const val KEY_CONFIRM_DOORS = "confirmDoors"
        const val KEY_LAST_UPDATE_CHECK = "lastUpdateCheck"
    }
}
