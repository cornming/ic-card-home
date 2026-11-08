package com.cornming.iccard

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.home.PermissionsState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppScreen(
    vm: MainViewModel,
    nfcSupported: Boolean,
    nfcEnabled: () -> Boolean,
    onOpenNfcSettings: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    val update by vm.update.collectAsState()
    val updateDialog by vm.updateDialog.collectAsState()
    val permission by vm.permission.collectAsState()
    val devices by vm.devices.collectAsState()
    val mappings by vm.mappings.collectAsState()
    val dryRun by vm.dryRun.collectAsState()
    val allowDoors by vm.allowDoors.collectAsState()
    val confirmDoors by vm.confirmDoors.collectAsState()
    val pendingExecution by vm.pendingExecution.collectAsState()
    val lastUid by vm.lastUid.collectAsState()
    val registering by vm.registering.collectAsState()
    val pendingUid by vm.pendingUid.collectAsState()
    val log by vm.log.collectAsState()

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("IC 卡控制", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "把卡貼在手機背面，依對照表控制 Google Home 裝置",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item { NfcCard(nfcSupported, nfcEnabled(), onOpenNfcSettings) }

            item {
                HomeCard(
                    state = permission,
                    deviceCount = devices.size,
                    onAuthorize = vm::requestPermission,
                    onRefresh = vm::refreshDevices,
                )
            }

            item {
                UpdateCard(
                    state = update,
                    onCheck = { vm.checkForUpdate(manual = true) },
                    onUpdate = vm::startUpdate,
                    onOpenInstallSettings = onOpenInstallSettings,
                )
            }

            item {
                SafetyCard(
                    dryRun = dryRun,
                    allowDoors = allowDoors,
                    confirmDoors = confirmDoors,
                    onDryRun = vm::setDryRun,
                    onAllowDoors = vm::setAllowDoors,
                    onConfirmDoors = vm::setConfirmDoors,
                )
            }

            item {
                ScanCard(
                    lastUid = lastUid,
                    registering = registering,
                    onStartRegister = vm::startRegistering,
                    onCancelRegister = vm::cancelRegistering,
                    onSimulate = { vm.onCardScanned(it.trim().uppercase(), simulated = true) },
                )
            }

            item { SectionTitle("已登記的卡（${mappings.size}）") }
            if (mappings.isEmpty()) {
                item { Text("還沒有登記任何卡片。按上方「新增卡片」後刷卡。") }
            } else {
                items(mappings, key = { it.uid }) { m ->
                    MappingRow(m, onDelete = { vm.deleteMapping(m.uid) })
                }
            }

            item { SectionTitle("裝置（${devices.size}）") }
            if (devices.isEmpty()) {
                item { Text("授權後會列出你在 Google Home 裡的裝置。") }
            } else {
                items(devices, key = { it.id }) { d -> DeviceItem(d) }
            }

            item { SectionTitle("記錄") }
            item { LogActions(log) }
            if (log.isEmpty()) {
                item { Text("目前沒有記錄。") }
            } else {
                items(log) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    val available = update as? UpdateState.Available
    if (updateDialog && available != null) {
        AlertDialog(
            onDismissRequest = vm::dismissUpdateDialog,
            title = { Text("有新版本 ${available.info.versionName}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("目前版本 ${BuildConfig.VERSION_NAME}。要現在下載並更新嗎？")
                    if (available.info.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(available.info.notes.take(600), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::startUpdate) { Text("下載並更新") } },
            dismissButton = { TextButton(onClick = vm::dismissUpdateDialog) { Text("稍後") } },
        )
    }

    pendingExecution?.let { mapping ->
        AlertDialog(
            onDismissRequest = vm::cancelExecution,
            title = { Text("要執行嗎？") },
            text = { Text("要對「${mapping.deviceName}」送出「開」嗎？10 秒內沒有確認會自動取消。") },
            confirmButton = { TextButton(onClick = vm::confirmExecution) { Text("確認") } },
            dismissButton = { TextButton(onClick = vm::cancelExecution) { Text("取消") } },
        )
    }

    val uid = pendingUid
    if (uid != null) {
        RegisterDialog(
            uid = uid,
            devices = devices.filter { it.hasOnOff },
            onSave = { device, action -> vm.saveMapping(uid, device, action) },
            onDismiss = vm::cancelRegistering,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun LogActions(log: List<String>) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(buildLogExport(log)))
            Toast.makeText(context, "已複製記錄，可以貼給 Claude", Toast.LENGTH_SHORT).show()
        }) { Text("複製記錄") }
        OutlinedButton(onClick = {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "IC 卡控制 操作記錄")
                putExtra(Intent.EXTRA_TEXT, buildLogExport(log))
            }
            context.startActivity(Intent.createChooser(shareIntent, "匯出操作記錄"))
        }) { Text("匯出記錄") }
    }
}

/** 完整操作記錄，含手機與 App 版本等脈絡，方便事後判斷問題（只在裝置本機產生，不會自動送出）。 */
private fun buildLogExport(log: List<String>): String = buildString {
    appendLine("IC 卡控制 操作記錄匯出")
    appendLine("App 版本：${BuildConfig.VERSION_NAME}")
    appendLine("手機：${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
    appendLine("匯出時間：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN).format(Date())}")
    appendLine()
    appendLine(if (log.isEmpty()) "記錄：無" else "記錄（最近 ${log.size} 筆，由新到舊）")
    log.forEach { appendLine(it) }
}.trimEnd()

@Composable
private fun NfcCard(supported: Boolean, enabled: Boolean, onOpenSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("NFC", fontWeight = FontWeight.Bold)
            when {
                !supported -> Text("這支手機沒有 NFC，無法讀卡。可以用下方「模擬刷卡」測試流程。")
                !enabled -> {
                    Text("NFC 目前關閉，請開啟後才能讀卡。")
                    OutlinedButton(onClick = onOpenSettings) { Text("開啟 NFC 設定") }
                }
                else -> Text("NFC 已開啟，App 在前景時可以讀卡。")
            }
        }
    }
}

@Composable
private fun HomeCard(
    state: PermissionsState?,
    deviceCount: Int,
    onAuthorize: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Google Home", fontWeight = FontWeight.Bold)
            when (state) {
                PermissionsState.GRANTED -> {
                    Text("已授權，讀到 $deviceCount 個裝置。")
                    OutlinedButton(onClick = onRefresh) { Text("重新整理裝置") }
                }
                null -> Text("正在確認授權狀態…")
                else -> {
                    Text("尚未授權（狀態：${state.name}）。")
                    Button(onClick = onAuthorize) { Text("授權 Google Home") }
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(
    state: UpdateState,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("版本與更新", fontWeight = FontWeight.Bold)
            Text("目前版本 ${BuildConfig.VERSION_NAME}")
            when (state) {
                UpdateState.Idle -> OutlinedButton(onClick = onCheck) { Text("檢查更新") }
                UpdateState.Checking -> Text("檢查中…")
                UpdateState.UpToDate -> {
                    Text("已是最新版本。")
                    OutlinedButton(onClick = onCheck) { Text("再檢查一次") }
                }
                is UpdateState.Available -> {
                    Text("有新版本 ${state.info.versionName}。")
                    Button(onClick = onUpdate) { Text("下載並更新") }
                }
                is UpdateState.Downloading -> {
                    Text("正在下載 ${state.info.versionName}（${state.percent}%）")
                    LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.NeedsPermission -> {
                    Text("下載完成。Android 需要你先允許這個 App 安裝更新。")
                    Button(onClick = onOpenInstallSettings) { Text("允許安裝更新") }
                    Text("允許後回到 App，會自動繼續安裝。", style = MaterialTheme.typography.bodySmall)
                }
                is UpdateState.Installing -> Text("已交給系統安裝，請在系統畫面按「更新」。")
                is UpdateState.Failed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onCheck) { Text("重新檢查") }
                }
            }
        }
    }
}

@Composable
private fun SafetyCard(
    dryRun: Boolean,
    allowDoors: Boolean,
    confirmDoors: Boolean,
    onDryRun: (Boolean) -> Unit,
    onAllowDoors: (Boolean) -> Unit,
    onConfirmDoors: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("安全設定", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("試運行")
                    Text("開啟時，刷卡只寫記錄，不會真的控制裝置。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = dryRun, onCheckedChange = onDryRun)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("允許控制疑似門禁的裝置")
                    Text(
                        "名稱或類型含「門、鎖、door、gate」的裝置預設一律擋下，避免誤開門。要用刷卡開門時才打開。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = allowDoors, onCheckedChange = onAllowDoors)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("門禁裝置刷卡後先確認")
                    Text(
                        "開啟時，刷到門禁卡會先跳出確認視窗，按「確認」才送出。關閉則刷卡後直接執行。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = confirmDoors, onCheckedChange = onConfirmDoors)
            }
        }
    }
}

@Composable
private fun ScanCard(
    lastUid: String?,
    registering: Boolean,
    onStartRegister: () -> Unit,
    onCancelRegister: () -> Unit,
    onSimulate: (String) -> Unit,
) {
    var simulatedUid by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("刷卡", fontWeight = FontWeight.Bold)
            Text("最近一次：${lastUid ?: "（尚未刷卡）"}")
            lastUid?.let { uidHint(it) }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (registering) {
                Text("請把要登記的卡貼在手機背面…")
                OutlinedButton(onClick = onCancelRegister) { Text("取消") }
            } else {
                Button(onClick = onStartRegister) { Text("新增卡片") }
            }
            OutlinedTextField(
                value = simulatedUid,
                onValueChange = { simulatedUid = it },
                label = { Text("模擬刷卡（輸入卡片 ID，不會控制裝置）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { onSimulate(simulatedUid) },
                enabled = simulatedUid.isNotBlank(),
            ) { Text("模擬刷卡") }
        }
    }
}

@Composable
private fun MappingRow(mapping: CardMapping, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("卡 ${mapping.uid}", fontWeight = FontWeight.Bold)
                Text("→「${mapping.deviceName}」${mapping.action.label}")
                if (mapping.looksLikeDoor) {
                    Text("疑似門禁裝置", color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onDelete) { Text("刪除") }
        }
    }
}

@Composable
private fun DeviceItem(device: DeviceRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(device.name, fontWeight = FontWeight.Bold)
            val state = when (device.isOn) {
                true -> "開"
                false -> "關"
                null -> "—"
            }
            Text("${if (device.online) "在線" else "離線"}　狀態：$state　${if (device.hasOnOff) "可開關" else "不可開關"}")
            Text(device.typeNames.joinToString("、"), style = MaterialTheme.typography.bodySmall)
            if (device.looksLikeDoor) {
                Text("疑似門禁裝置", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RegisterDialog(
    uid: String,
    devices: List<DeviceRow>,
    onSave: (DeviceRow, CardAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var action by remember { mutableStateOf(CardAction.TOGGLE) }
    val selected = devices.firstOrNull { it.id == selectedId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登記卡片 $uid") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                uidHint(uid)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                if (devices.isEmpty()) {
                    Text("沒有可開關的裝置。請先授權並確認裝置在線。")
                }
                devices.forEach { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = d.id == selectedId, role = Role.RadioButton) { selectedId = d.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = d.id == selectedId, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(d.name)
                            if (d.looksLikeDoor) {
                                Text("疑似門禁，預設會被擋下", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("刷卡時要做什麼")
                if (selected?.looksLikeDoor == true) {
                    // Inching relay: it returns to off by itself, so only "open" is offered.
                    Text("門禁繼電器只提供「開」（點動後會自動恢復）。")
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CardAction.entries.forEach { a ->
                            FilterChip(selected = action == a, onClick = { action = a }, label = { Text(a.label) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let { onSave(it, action) } }, enabled = selected != null) {
                Text("儲存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
