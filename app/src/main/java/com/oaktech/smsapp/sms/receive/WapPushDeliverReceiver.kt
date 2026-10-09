package com.oaktech.smsapp.sms.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.oaktech.smsapp.sms.mms.IncomingMmsWorker

/**
 * Receives MMS push notifications.
 *
 * Android delivers two flavors to a pending WAP push: `WAP_PUSH_DELIVER` for
 * the default SMS app, and `WAP_PUSH_RECEIVED` when the app carries the WAP_DASH
 * role. Both are registered on `application/vnd.wap.mms-message` in the
 * manifest, so the role transition does not change how an arrival is handled.
 */
class WapPushDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_WAP_PUSH_DELIVER && action != ACTION_WAP_PUSH_RECEIVED) return
        if (intent.type != MMS_CONTENT_TYPE) {
            Log.d(LogTags.RECEIVE, "Ignored WAP push of type ${intent.type}")
            return
        }

        val pdu = intent.getByteArrayExtra(EXTRA_DATA) ?: return
        if (pdu.isEmpty()) return

        // The fetch and its acknowledgements are handed to WorkManager before
        // this returns: they are network transactions that can outlast the
        // broadcast's budget several times over. Nothing is lost if the queue
        // is full or the process dies — the carrier retransmits an
        // unacknowledged notification.
        if (!IncomingMmsWorker.enqueue(context, pdu)) {
            Log.w(LogTags.RECEIVE, "Could not queue incoming MMS; the carrier will repush it")
        }
    }

    private companion object {
        const val EXTRA_DATA = "data"
        const val ACTION_WAP_PUSH_DELIVER = "android.provider.Telephony.WAP_PUSH_DELIVER"
        const val ACTION_WAP_PUSH_RECEIVED = "android.provider.Telephony.WAP_PUSH_RECEIVED"
        const val MMS_CONTENT_TYPE = "application/vnd.wap.mms-message"
    }
}