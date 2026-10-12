package com.anttech.smsapp.sms.respond

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.anttech.smsapp.sms.receive.LogTags
import com.anttech.smsapp.sms.send.SendGraph
import com.anttech.smsapp.ui.compose.ComposeIntentParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles RESPOND_VIA_MESSAGE so other apps can offer "reply with message".
 *
 * Declared because the system requires it before granting ROLE_SMS. The reply is
 * sent from here rather than from the UI: the user may be replying from a
 * contact card or a call screen, where launching this app's composer first would
 * be a detour.
 *
 * Runs on [START_NOT_STICKY] and stops itself. There is no state to recover, and
 * a redelivery would resend a reply the user already sent.
 */
class RespondViaMessageService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recipients = ComposeIntentParser.recipientsFrom(intent?.dataString)
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?: ComposeIntentParser.bodyFrom(intent?.dataString).orEmpty()

        when {
            recipients.isEmpty() -> {
                Log.w(LogTags.SEND, "RESPOND_VIA_MESSAGE with no recipient: ${intent?.dataString}")
                stopSelf(startId)
            }

            body.isBlank() -> {
                // Nothing typed yet. The caller shows its own compose UI, so
                // there is no message to send.
                Log.d(LogTags.SEND, "RESPOND_VIA_MESSAGE with empty body, ignoring")
                stopSelf(startId)
            }

            else -> sendReply(recipients.first(), body, startId)
        }

        return START_NOT_STICKY
    }

    private fun sendReply(recipient: String, body: String, startId: Int) {
        scope.launch {
            try {
                SendGraph.coordinator(applicationContext).send(recipient, body)
            } catch (t: Throwable) {
                Log.e(LogTags.SEND, "Reply from RESPOND_VIA_MESSAGE failed", t)
            } finally {
                stopSelf(startId)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
