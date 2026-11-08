package com.cornming.iccard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private lateinit var nfc: NfcReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Creating the controller registers the permission launcher, which must happen before STARTED.
        vm.attach(HomeController(this))
        nfc = NfcReader(this) { uid -> vm.onCardScanned(uid) }
        vm.checkForUpdate(manual = false)
        // Started by a card tap while the app was closed. A recreated activity (savedInstanceState != null)
        // still carries the old intent, so it must not replay the tap.
        if (savedInstanceState == null) handleCardIntent(intent, "冷啟動")

        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                AppScreen(
                    vm = vm,
                    nfcSupported = nfc.supported,
                    nfcEnabled = { nfc.enabled },
                    onOpenNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                    onOpenInstallSettings = { startActivity(vm.installPermissionIntent()) },
                )
            }
        }
    }

    // Card tapped while the app was running in the background (launchMode is singleTop).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCardIntent(intent, "背景喚回")
    }

    /**
     * Feeds a system-dispatched card tap into the same path as a foreground read, confirm dialog included.
     * [source] is just a label for 記錄 ("冷啟動" or "背景喚回"); it never changes what happens.
     * Every branch logs something distinguishable, even the ones that bail out, so a tap that produces no
     * visible result on screen still leaves a trace instead of looking like nothing happened at all.
     */
    private fun handleCardIntent(intent: Intent, source: String) {
        // An ordinary launcher tap (no NFC involved) carries none of these actions; stay silent for that
        // case, it would otherwise log a line on every normal app open.
        if (!NfcReader.isDispatchIntent(intent)) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
            vm.logDiagnostic("[$source] 已忽略：這個 Intent 來自 Recents 歷史，避免重播舊的刷卡")
            return
        }
        val uid = NfcReader.uidFromIntent(intent)
        if (uid == null) {
            vm.logDiagnostic("[$source] 偵測到 NFC 事件，但讀不到卡片 ID")
            return
        }
        // Replace the intent so the same tap can never be handled twice.
        setIntent(Intent(this, MainActivity::class.java))
        vm.onCardScanned(uid, source = source)
    }

    // Reader mode only works while the app is in the foreground.
    override fun onResume() {
        super.onResume()
        nfc.start()
        vm.onAppResumed()
    }

    override fun onPause() {
        nfc.stop()
        super.onPause()
    }
}
