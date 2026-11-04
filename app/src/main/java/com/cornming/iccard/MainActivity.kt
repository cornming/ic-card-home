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
