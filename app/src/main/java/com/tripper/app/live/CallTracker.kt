package com.tripper.app.live

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

class CallTracker(private val context: Context) {

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private var isListening = false

    private val phoneStateListener = @Suppress("DEPRECATION")
    object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    val name = lookupContactName(phoneNumber ?: "")
                    LiveDataRepository.updateCall(
                        CallInfo(
                            callerName = name,
                            callerNumber = phoneNumber ?: "",
                            isRinging = true,
                        )
                    )
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    LiveDataRepository.updateCall(CallInfo())
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {}
            }
        }
    }

    fun start() {
        if (isListening) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return

        @Suppress("DEPRECATION")
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        isListening = true
    }

    fun stop() {
        if (!isListening) return
        @Suppress("DEPRECATION")
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        isListening = false
    }

    private fun lookupContactName(number: String): String {
        if (number.isBlank()) return "Unknown"
        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
            .appendPath(number)
            .build()
        val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return it.getString(nameIndex)
                }
            }
        }
        return number
    }
}
