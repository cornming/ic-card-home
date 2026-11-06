package com.cornming.iccard

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build

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
        onUid(hex(tag.id))
    }

    companion object {
        private val DISPATCH_ACTIONS = setOf(
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED,
        )

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }

        /**
         * UID of a card the system handed to the app (tapped while the app was not in the foreground),
         * or null if the intent is not an NFC dispatch.
         * Only the Tag object counts. The loose EXTRA_ID bytes are ignored because any app can put them
         * in an intent, while a real Tag cannot be forged without hidden APIs.
         */
        fun uidFromIntent(intent: Intent): String? {
            val action = intent.action ?: return null
            if (action !in DISPATCH_ACTIONS) return null
            val tag = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            return tag?.let { hex(it.id) }
        }
    }
}
