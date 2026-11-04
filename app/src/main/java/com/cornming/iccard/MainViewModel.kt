package com.cornming.iccard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.home.HomeException
import com.google.home.PermissionsResultStatus
import com.google.home.PermissionsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = CardStore(app)
    private var controller: HomeController? = null
    private var permissionJob: Job? = null

    private val _permission = MutableStateFlow<PermissionsState?>(null)
    val permission: StateFlow<PermissionsState?> = _permission

    private val _devices = MutableStateFlow<List<DeviceRow>>(emptyList())
    val devices: StateFlow<List<DeviceRow>> = _devices

    private val _mappings = MutableStateFlow(store.load())
    val mappings: StateFlow<List<CardMapping>> = _mappings

    private val _dryRun = MutableStateFlow(store.dryRun)
    val dryRun: StateFlow<Boolean> = _dryRun

    private val _allowDoors = MutableStateFlow(store.allowDoorDevices)
    val allowDoors: StateFlow<Boolean> = _allowDoors

    private val _confirmDoors = MutableStateFlow(store.confirmDoors)
    val confirmDoors: StateFlow<Boolean> = _confirmDoors

    /** A door mapping waiting for the user to press "confirm" on the phone. */
    private val _pendingExecution = MutableStateFlow<CardMapping?>(null)
    val pendingExecution: StateFlow<CardMapping?> = _pendingExecution

    private val _lastUid = MutableStateFlow<String?>(null)
    val lastUid: StateFlow<String?> = _lastUid

    /** True while the user pressed "add card" and the next tap should be registered, not executed. */
    private val _registering = MutableStateFlow(false)
    val registering: StateFlow<Boolean> = _registering

    /** UID waiting for the user to pick a device and an action. */
    private val _pendingUid = MutableStateFlow<String?>(null)
    val pendingUid: StateFlow<String?> = _pendingUid

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val updater = UpdateManager(app)
    private var downloadedApk: java.io.File? = null

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update

    /** True while the "new version available" dialog should be on screen. */
    private val _updateDialog = MutableStateFlow(false)
    val updateDialog: StateFlow<Boolean> = _updateDialog

    private var debounceUid: String? = null
    private var debounceAt = 0L

    init {
        // Failures reported by the installer arrive asynchronously, after the app handed over the APK.
        viewModelScope.launch {
            UpdateManager.installEvents.collect { message ->
                addLog(message)
                _update.value = UpdateState.Failed(message)
            }
        }
    }

    /** [manual] checks always run and report "already latest"; automatic checks run at most every 6 hours. */
    fun checkForUpdate(manual: Boolean) {
        when (_update.value) {
            is UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing -> return
            else -> Unit
        }
        val now = System.currentTimeMillis()
        if (!manual && now - store.lastUpdateCheck < UPDATE_CHECK_INTERVAL_MS) return
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val info = updater.check()
                store.lastUpdateCheck = now
                if (info == null) {
                    _update.value = UpdateState.UpToDate
                    if (manual) addLog("已是最新版本 ${BuildConfig.VERSION_NAME}")
                } else {
                    _update.value = UpdateState.Available(info)
                    _updateDialog.value = true
                    addLog("發現新版本 ${info.versionName}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed automatic check stays quiet; only a manual check shows the error.
                _update.value = if (manual) UpdateState.Failed("檢查更新失敗：${e.message}") else UpdateState.Idle
                addLog("檢查更新失敗：${e.message}")
            }
        }
    }

    fun dismissUpdateDialog() {
        _updateDialog.value = false
    }

    /** Downloads the new APK, verifies it and starts the system installer. */
    fun startUpdate() {
        val info = when (val s = _update.value) {
            is UpdateState.Available -> s.info
            is UpdateState.NeedsPermission -> s.info
            else -> return
        }
        _updateDialog.value = false
        viewModelScope.launch {
            try {
                val apk = downloadedApk?.takeIf { it.exists() && it.name.contains(info.versionName) }
                    ?: run {
                        _update.value = UpdateState.Downloading(info, 0)
                        updater.download(info) { percent -> _update.value = UpdateState.Downloading(info, percent) }
                    }
                downloadedApk = apk
                continueInstall(info, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _update.value = UpdateState.Failed("更新失敗：${e.message}")
                addLog("更新失敗：${e.message}")
            }
        }
    }

    /** Called when the app returns to the foreground, e.g. after the user allowed installs in system settings. */
    fun onAppResumed() {
        val state = _update.value
        if (state is UpdateState.NeedsPermission && updater.canInstall()) {
            val apk = downloadedApk ?: return
            viewModelScope.launch {
                try {
                    continueInstall(state.info, apk)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _update.value = UpdateState.Failed("更新失敗：${e.message}")
                }
            }
        }
    }

    private suspend fun continueInstall(info: ReleaseInfo, apk: java.io.File) {
        if (!updater.canInstall()) {
            _update.value = UpdateState.NeedsPermission(info)
            return
        }
        _update.value = UpdateState.Installing(info)
        addLog("已下載 ${info.versionName}，交給系統安裝")
        updater.install(apk)
    }

    fun installPermissionIntent() = updater.permissionIntent()

    /** Called from onCreate. Re-attaching after the activity was recreated replaces the old controller. */
    fun attach(controller: HomeController) {
        this.controller = controller
        permissionJob?.cancel()
        permissionJob = viewModelScope.launch {
            controller.permissionState().collect { state ->
                _permission.value = state
                if (state == PermissionsState.GRANTED) refreshDevices()
            }
        }
    }

    fun requestPermission() {
        val c = controller ?: return
        viewModelScope.launch {
            try {
                val result = c.requestPermission()
                when (result.status) {
                    PermissionsResultStatus.SUCCESS -> addLog("授權完成")
                    PermissionsResultStatus.CANCELLED -> addLog("授權已取消")
                    else -> addLog("授權失敗：${result.status}")
                }
            } catch (e: HomeException) {
                addLog("授權錯誤：${e.message}")
            }
        }
    }

    fun refreshDevices() {
        val c = controller ?: return
        viewModelScope.launch {
            try {
                _devices.value = c.loadDevices()
                addLog("讀到 ${_devices.value.size} 個裝置")
            } catch (e: HomeException) {
                addLog("讀取裝置失敗：${e.message}")
            }
        }
    }

    fun setDryRun(value: Boolean) {
        store.dryRun = value
        _dryRun.value = value
        addLog(if (value) "已開啟試運行：刷卡只會記錄，不會控制裝置" else "已關閉試運行：刷卡會真的控制裝置")
    }

    fun setAllowDoors(value: Boolean) {
        store.allowDoorDevices = value
        _allowDoors.value = value
        addLog(if (value) "已允許控制疑似門禁的裝置" else "已禁止控制疑似門禁的裝置")
    }

    fun setConfirmDoors(value: Boolean) {
        store.confirmDoors = value
        _confirmDoors.value = value
        addLog(if (value) "疑似門禁裝置：刷卡後需先確認" else "疑似門禁裝置：刷卡後直接執行")
    }

    fun confirmExecution() {
        val mapping = _pendingExecution.value ?: return
        _pendingExecution.value = null
        runMapping(mapping)
    }

    fun cancelExecution() {
        val mapping = _pendingExecution.value ?: return
        _pendingExecution.value = null
        addLog("已取消對「${mapping.deviceName}」的操作")
    }

    fun startRegistering() {
        _registering.value = true
    }

    fun cancelRegistering() {
        _registering.value = false
        _pendingUid.value = null
    }

    fun saveMapping(uid: String, device: DeviceRow, requested: CardAction) {
        // A door relay set to "inching" only needs ON: it closes again by itself, and OFF/TOGGLE are meaningless.
        val action = if (device.looksLikeDoor) CardAction.ON else requested
        val updated = _mappings.value.filterNot { it.uid == uid } +
            CardMapping(uid, device.id, device.name, action, device.looksLikeDoor)
        store.save(updated)
        _mappings.value = updated
        _pendingUid.value = null
        addLog("已登記：卡 $uid → 「${device.name}」${action.label}")
    }

    fun deleteMapping(uid: String) {
        val updated = _mappings.value.filterNot { it.uid == uid }
        store.save(updated)
        _mappings.value = updated
        addLog("已刪除卡 $uid 的對照")
    }

    /**
     * Entry point for both real taps and the "simulate" button.
     * A simulated tap never touches a device, it only logs what would happen.
     */
    fun onCardScanned(uid: String, simulated: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!simulated) {
            // A card held against the phone fires repeatedly; without this a toggle would flip back and forth.
            if (uid == debounceUid && now - debounceAt < DEBOUNCE_MS) return
            debounceUid = uid
            debounceAt = now
        }
        if (!simulated && _pendingExecution.value != null) {
            addLog("上一個操作還在等你確認，已忽略這次刷卡")
            return
        }
        _lastUid.value = uid

        if (_registering.value) {
            _registering.value = false
            _pendingUid.value = uid
            addLog("收到卡片 $uid，請選擇要控制的裝置")
            return
        }

        val mapping = _mappings.value.firstOrNull { it.uid == uid }
        if (mapping == null) {
            addLog("未登記的卡：$uid")
            return
        }

        val tag = if (simulated) "[模擬]" else ""
        if (mapping.looksLikeDoor && !_allowDoors.value) {
            addLog("$tag 已擋下：「${mapping.deviceName}」疑似門禁，需先在安全設定允許")
            return
        }
        if (simulated || _dryRun.value) {
            addLog("${if (simulated) "[模擬]" else "[試運行]"} 會對「${mapping.deviceName}」執行：${doorAction(mapping).label}")
            return
        }

        if (mapping.looksLikeDoor && _confirmDoors.value) {
            // Second gate: nothing is sent until the user presses "confirm" on the phone.
            _pendingExecution.value = mapping
            addLog("等待確認：要對「${mapping.deviceName}」執行：${doorAction(mapping).label}")
            viewModelScope.launch {
                delay(CONFIRM_TIMEOUT_MS)
                if (_pendingExecution.value == mapping) {
                    _pendingExecution.value = null
                    addLog("確認逾時，已取消對「${mapping.deviceName}」的操作")
                }
            }
            return
        }
        runMapping(mapping)
    }

    /** Door relays are set to inching in eWeLink, so ON is the only command that makes sense. */
    private fun doorAction(mapping: CardMapping): CardAction =
        if (mapping.looksLikeDoor) CardAction.ON else mapping.action

    private fun runMapping(mapping: CardMapping) {
        val c = controller
        if (c == null) {
            addLog("尚未連上 Google Home，無法控制")
            return
        }
        viewModelScope.launch {
            addLog(c.execute(mapping.deviceId, doorAction(mapping)))
            refreshDevices()
        }
    }

    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date())
        _log.update { (listOf("$time  $message") + it).take(MAX_LOG) }
    }

    private companion object {
        const val DEBOUNCE_MS = 2_000L
        const val CONFIRM_TIMEOUT_MS = 10_000L
        const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val MAX_LOG = 40
    }
}
