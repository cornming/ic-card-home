package com.cornming.iccard

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag

/**
 * Reads the UID of any ISO 14443 / 15693 / FeliCa card while the activity is in the foreground.
 * Reader mode keeps the phone from launching other apps when a card is tapped.
 */
class NfcReader(
    private val activity: Activity,
    private val onUid: (String) -> Unit,
) : NfcAdapter.ReaderCallback {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    val supported: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    fun start() {
        adapter?.enableReaderMode(
            activity,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    fun stop() {
        adapter?.disableReaderMode(activity)
    }

    // Called on a binder thread, not the main thread.
    override fun onTagDiscovered(tag: Tag) {
        val uid = tag.id.joinToString("") { "%02X".format(it) }
        onUid(uid)
    }
}
