package com.oaktech.smsapp

import android.app.Application
import android.util.Log
import com.oaktech.smsapp.sms.mms.MmsAttachmentCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        com.oaktech.smsapp.notify.NotificationChannels.ensure(this)

        // Sweep attachment bytes whose message (or scribbled queue) is gone. Off
        // the main thread and best-effort: it is housekeeping, not a startup
        // dependency, so a failure must never delay or crash the app.
        scope.launch {
            runCatching { MmsAttachmentCleaner.pruneStore(this@SmsApp) }
                .onFailure { Log.w(TAG, "Attachment orphan sweep failed", it) }
        }
    }

    companion object {
        const val TAG = "SmsApp"
    }
}
