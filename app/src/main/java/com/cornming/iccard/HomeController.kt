package com.cornming.iccard

import android.util.Log
import androidx.activity.ComponentActivity
import com.google.home.ConnectivityState
import com.google.home.ConsentScreenOptions
import com.google.home.FactoryRegistry
import com.google.home.ForcePermissionFlow
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.HomeDevice
import com.google.home.HomeException
import com.google.home.PermissionsResult
import com.google.home.PermissionsState
import com.google.home.matter.standard.BasicInformation
import com.google.home.matter.standard.ColorTemperatureLightDevice
import com.google.home.matter.standard.DimmableLightDevice
import com.google.home.matter.standard.DoorLockDevice
import com.google.home.matter.standard.ExtendedColorLightDevice
import com.google.home.matter.standard.GenericSwitchDevice
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.OnOffLightDevice
import com.google.home.matter.standard.OnOffLightSwitchDevice
import com.google.home.matter.standard.OnOffPluginUnitDevice
import com.google.home.matter.standard.RootNodeDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Outcome of one [HomeController.execute] call. */
data class ExecuteResult(val message: String, val ok: Boolean, val retryable: Boolean)

/**
 * Thin wrapper around the Google Home APIs. Only the OnOff trait is ever used to control a device.
 * Door locks are registered so that they show up (and can be flagged), but there is no code path
 * that sends a lock or unlock command.
 */
class HomeController(activity: ComponentActivity) {

    private val registry = FactoryRegistry(
        types = listOf(
            OnOffLightDevice,
            OnOffPluginUnitDevice,
            OnOffLightSwitchDevice,
            DimmableLightDevice,
            ColorTemperatureLightDevice,
            ExtendedColorLightDevice,
            GenericSwitchDevice,
            DoorLockDevice,
            RootNodeDevice,
        ),
        traits = listOf(OnOff, BasicInformation),
    )

    private val config = HomeConfig(
        coroutineContext = Dispatchers.IO,
        factoryRegistry = registry,
        homePlatformScope = HomeConfig.HomePlatformScope.HOME_PLATFORM_SCOPE_VERSION_1,
    )

    private val client: HomeClient = Home.getClient(activity.applicationContext, homeConfig = config)

    init {
        // Must happen before the activity is STARTED, otherwise the consent screen cannot launch.
        client.registerActivityResultCallerForPermissions(activity)
    }

    fun permissionState(): Flow<PermissionsState> = client.hasPermissions()

    suspend fun requestPermission(): PermissionsResult = client.requestPermissions(
        ForcePermissionFlow.FORCE_LAUNCH,
        consentScreenOptions = ConsentScreenOptions(
            structureId = null,
            allowedStructureIds = emptyList(),
            isAllowStructureChange = false,
        ),
    )

    /** Snapshot of every device the user granted access to. */
    suspend fun loadDevices(): List<DeviceRow> = withContext(Dispatchers.IO) {
        client.devices().first().map { describe(it) }.sortedBy { it.name }
    }

    private suspend fun describe(device: HomeDevice): DeviceRow {
        val types = device.types().first()
        val functional = types.filter { it !is RootNodeDevice }
        val primary = functional.firstOrNull() ?: types.firstOrNull()
        val typeNames = types.map { it.factory.toString().substringAfterLast(".") }
        val onOff = types.flatMap { it.traits() }.filterIsInstance<OnOff>().firstOrNull()
        val connectivity = primary?.metadata?.sourceConnectivity?.connectivityState
        val online = connectivity == ConnectivityState.ONLINE ||
            connectivity == ConnectivityState.PARTIALLY_ONLINE
        Log.i(TAG, "device '${device.name}' id=${device.id.id} types=$typeNames online=$online onOff=${onOff?.onOff}")
        return DeviceRow(
            id = device.id.id,
            name = device.name,
            typeNames = typeNames,
            online = online,
            hasOnOff = onOff != null,
            isOn = onOff?.onOff,
            looksLikeDoor = looksLikeDoor(device.name, typeNames),
        )
    }

    /**
     * Sends on/off/toggle through the OnOff trait. [ExecuteResult.message] is the line for the log;
     * [ExecuteResult.retryable] says whether trying again could plausibly help (a transient Home error,
     * or a device list that was not ready yet) as opposed to a permanent one (the device has no OnOff).
     */
    suspend fun execute(deviceId: String, action: CardAction): ExecuteResult = withContext(Dispatchers.IO) {
        try {
            val device = client.devices().first().firstOrNull { it.id.id == deviceId }
                ?: return@withContext ExecuteResult("找不到裝置，可能已被移除或未授權", ok = false, retryable = true)
            val trait = device.types().first().flatMap { it.traits() }
                .filterIsInstance<OnOff>().firstOrNull()
                ?: return@withContext ExecuteResult("「${device.name}」沒有開關功能", ok = false, retryable = false)
            when (action) {
                CardAction.ON -> trait.on()
                CardAction.OFF -> trait.off()
                CardAction.TOGGLE -> if (trait.onOff == true) trait.off() else trait.on()
            }
            ExecuteResult("已對「${device.name}」執行：${action.label}", ok = true, retryable = false)
        } catch (e: HomeException) {
            Log.w(TAG, "execute failed", e)
            ExecuteResult("控制失敗：${e.message}", ok = false, retryable = true)
        }
    }

    companion object {
        private const val TAG = "HomeController"
        private val DOOR_HINT = Regex("門|鎖|門禁|車庫|door|gate|lock|garage", RegexOption.IGNORE_CASE)

        fun looksLikeDoor(name: String, typeNames: List<String>): Boolean =
            DOOR_HINT.containsMatchIn(name) || typeNames.any { DOOR_HINT.containsMatchIn(it) }
    }
}
