package com.dansemur.ironman.ota

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "No message"

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "PackageInstaller: Update installed successfully! $message")
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.w(TAG, "PackageInstaller: User action required! Launching confirmation activity.")
                @Suppress("DEPRECATION")
                val confirmationIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmationIntent != null) {
                    confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmationIntent)
                }
            }
            else -> {
                Log.e(TAG, "PackageInstaller: Silent update failed with status=$status ($message)")
            }
        }
    }

    companion object {
        private const val TAG = "UpdateReceiver"
    }
}
