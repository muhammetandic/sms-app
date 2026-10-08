package com.oaktech.smsapp.sms.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Receives SMS_DELIVER, which Android only delivers to the default SMS app.
 * Bulk parsing and persistence is delegated off the main thread via goAsync().
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val parts = messages.mapNotNull { part ->
            part.displayMessageBody?.let { body ->
                SmsPart(
                    originatingAddress = part.displayOriginatingAddress.orEmpty(),
                    messageBody = body,
                    timestampMillis = part.timestampMillis,
                )
            }
        }
        if (parts.isEmpty()) return

        val pendingResult = goAsync()
        SmsReceiveWorker.enqueue(context, parts) {
            pendingResult.finish()
        }
    }
}

/** One SMS PDU. Multi-part messages arrive as several [SmsPart]s that must be joined. */
data class SmsPart(
    val originatingAddress: String,
    val messageBody: String,
    val timestampMillis: Long,
)

internal object LogTags {
    const val RECEIVE = "SmsReceive"
    const val SEND = "SmsSend"
    const val ROLE = "SmsRole"
    const val STORE = "SmsStore"

    fun d(throwable: Throwable): String = Log.getStackTraceString(throwable)
}