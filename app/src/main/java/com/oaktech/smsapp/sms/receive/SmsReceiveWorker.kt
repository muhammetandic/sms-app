package com.oaktech.smsapp.sms.receive

import android.content.Context
import android.util.Log
import com.oaktech.smsapp.contacts.ContactNameResolver
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.notify.IncomingMessageNotifier
import com.oaktech.smsapp.sms.block.BlockedSenders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Off-main-thread entry point for incoming SMS.
 *
 * Runs inside the 10 second budget a BroadcastReceiver's pending result grants,
 * so it reuses one process-wide scope instead of creating a scope per message.
 */
object SmsReceiveWorker {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(context: Context, parts: List<SmsPart>, onComplete: () -> Unit) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                handle(appContext, parts)
            } catch (throwable: Throwable) {
                Log.e(LogTags.RECEIVE, "Failed to handle incoming SMS", throwable)
            } finally {
                onComplete()
            }
        }
    }

    private suspend fun handle(context: Context, parts: List<SmsPart>) {
        val message = MultipartReassembler.reassemble(parts) ?: run {
            Log.w(LogTags.RECEIVE, "Discarded malformed multipart message (${parts.size} part(s))")
            return
        }

        val graph = DataGraph.get(context)
        val rowId = graph.messageRepository.recordInbound(message, partCount = message.parts)
        if (rowId == null) {
            Log.d(LogTags.RECEIVE, "Skipped duplicate message from ${message.address}")
            return
        }

        // Mirror before the spam and mute branches below: the system store keeps
        // every message this app receives, whichever folder it lands in here.
        // A refused write (role lost while the broadcast was in flight) leaves
        // the local copy alone rather than dropping the message.
        graph.messageRepository.mirrorInboundToProvider(rowId, message)

        val conversationId = graph.messageRepository.conversationIdFor(message.address)
        if (conversationId != null) {
            val spam = graph.messageRepository.isSpam(conversationId)
            // A conversation the user moved to spam stays quiet. A blocked sender
            // lands in spam the moment the message is stored, also quiet. Both are
            // kept visible in their folders; only the alert is suppressed.
            val freshlyBlocked = !spam && BlockedSenders(context).isBlocked(message.address)
            if (freshlyBlocked) {
                graph.messageRepository.setConversationSpam(conversationId, true)
            }
            if (spam || freshlyBlocked) {
                Log.d(
                    LogTags.RECEIVE,
                    "Stored ${if (freshlyBlocked) "blocked" else "spam"} message from " +
                        "${message.address} without notifying",
                )
                return
            }
            // A muted conversation still stores and counts the message; only the
            // notification is suppressed. Unlike spam, the row stays in the
            // inbox so the unread badge keeps speaking.
            if (graph.messageRepository.isMuted(conversationId)) {
                Log.d(
                    LogTags.RECEIVE,
                    "Stored muted message from ${message.address} without notifying",
                )
                return
            }
        }

        IncomingMessageNotifier(context).notify(
            message = message,
            messageId = rowId,
            conversationId = conversationId,
            // Contact lookup needs the contacts permission, which a role loss
            // revokes; the resolver returns null then and the number stands in.
            displayName = ContactNameResolver(context).displayName(message.address),
        )
        Log.d(
            LogTags.RECEIVE,
            "Stored inbound message from ${message.address} (${message.parts} part(s), row $rowId)",
        )
    }
}