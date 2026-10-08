package com.oaktech.smsapp.sms.send

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.oaktech.smsapp.sms.receive.LogTags

/**
 * Receives per-part send results and delivery reports for outgoing messages.
 *
 * Declared in the manifest because the platform delivers these to the sending
 * app even when it was not running when the result arrived; a runtime-registered
 * receiver would miss them.
 *
 * The result *action* is `android.intent.action.VIEW` for sent and
 * `android.intent.action.DIAL` for delivery. `SmsManager.RESULT_SENT_ACTION` and
 * `RESULT_DELIVERY_ACTION` were removed from the public SDK, and the platform
 * sends these exact strings for the pending intents it is given, so they are
 * declared here rather than referenced.
 */
class SmsSendResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND) ?: return

        val appContext = context.applicationContext
        val token = intent.getLongExtra(EXTRA_TOKEN, -1L)
        val rowId = intent.getLongExtra(EXTRA_ROW_ID, -1L)
        if (token < 0 || rowId < 0) {
            Log.w(LogTags.SEND, "Ignoring send result with missing extras")
            return
        }

        val coordinator = SendGraph.coordinator(appContext)
        when (kind) {
            KIND_SENT -> {
                val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0)
                val code = resultCode
                Log.d(LogTags.SEND, "Part $partIndex of row $rowId reported code $code")
                coordinator.onPartResult(token, rowId, partIndex, code)
            }

            KIND_DELIVERED -> {
                Log.d(LogTags.SEND, "Row $rowId reported delivery")
                coordinator.onDelivered(token, rowId)
            }
        }
    }

    companion object {
        const val EXTRA_KIND = "com.oaktech.smsapp.extra.KIND"
        const val EXTRA_TOKEN = "com.oaktech.smsapp.extra.TOKEN"
        const val EXTRA_ROW_ID = "com.oaktech.smsapp.extra.ROW_ID"
        const val EXTRA_PART_INDEX = "com.oaktech.smsapp.extra.PART_INDEX"

        const val KIND_SENT = "sent"
        const val KIND_DELIVERED = "delivered"

        fun sentIntent(context: Context, token: Long, rowId: Long, partIndex: Int): Intent =
            Intent(context, SmsSendResultReceiver::class.java)
                .putExtra(EXTRA_KIND, KIND_SENT)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_ROW_ID, rowId)
                .putExtra(EXTRA_PART_INDEX, partIndex)

        fun deliveryIntent(context: Context, token: Long, rowId: Long): Intent =
            Intent(context, SmsSendResultReceiver::class.java)
                .putExtra(EXTRA_KIND, KIND_DELIVERED)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_ROW_ID, rowId)
    }
}