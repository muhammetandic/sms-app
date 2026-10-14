package com.anttech.smsapp.sms.receive

import android.content.Context
import android.util.Log
import com.anttech.smsapp.data.DataGraph
import com.anttech.smsapp.notify.IncomingMessageNotifier
import com.anttech.smsapp.sms.block.BlockedSenders

/**
 * Off-main-thread processing for incoming SMS.
 */
object SmsReceiveWorker {

    internal suspend fun handle(context: Context, parts: List<SmsPart>) {
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

        val conversationId = graph.conversationRepository.conversationIdFor(message.address)
        if (conversationId != null) {
            val spam = graph.conversationRepository.isSpam(conversationId)
            // A conversation the user moved to spam stays quiet. A blocked sender
            // lands in spam the moment the message is stored, also quiet. Both are
            // kept visible in their folders; only the alert is suppressed.
            val freshlyBlocked = !spam && BlockedSenders(context).isBlocked(message.address)
            if (freshlyBlocked) {
                graph.conversationRepository.setConversationSpam(conversationId, true)
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
            if (graph.conversationRepository.isMuted(conversationId)) {
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
            displayName = DataGraph.get(context).contactNames.displayName(message.address),
        )
        Log.d(
            LogTags.RECEIVE,
            "Stored inbound message from ${message.address} (${message.parts} part(s), row $rowId)",
        )
    }
}
