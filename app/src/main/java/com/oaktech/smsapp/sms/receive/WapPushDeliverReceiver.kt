package com.oaktech.smsapp.sms.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Declared only so the app stays eligible for ROLE_SMS.
 *
 * The system's role definition (`roles.xml`, `android.app.role.SMS`) requires a
 * receiver for `WAP_PUSH_DELIVER` with the MMS MIME type; an app without it
 * cannot be made the default SMS app at all, which would break SMS receive.
 * This app handles SMS only, so the broadcast is acknowledged and ignored: no
 * MMS PDU is ever parsed, downloaded or stored.
 */
class WapPushDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(LogTags.RECEIVE, "Ignoring WAP push: MMS is not supported")
    }
}
