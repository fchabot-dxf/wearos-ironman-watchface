package com.dansemur.ironman.complication

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

class ComplicationUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("ComplicationUpdateReceiver", "Received intent ${intent.action}: requesting updateAll for TestSuiteComplicationService")
        try {
            val component = ComponentName(context, TestSuiteComplicationService::class.java)
            val requester = ComplicationDataSourceUpdateRequester.create(
                context = context,
                complicationDataSourceComponent = component
            )
            requester.requestUpdateAll()
            Log.d("ComplicationUpdateReceiver", "Successfully requested complication updateAll!")
        } catch (e: Exception) {
            Log.e("ComplicationUpdateReceiver", "Failed to request complication updateAll", e)
        }
    }
}
