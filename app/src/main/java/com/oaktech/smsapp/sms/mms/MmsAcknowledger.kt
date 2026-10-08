package com.oaktech.smsapp.sms.mms

import android.util.Log
import com.oaktech.smsapp.sms.receive.LogTags

/**
 * Posts the acknowledgement PDUs an incoming MMS transaction needs.
 *
 * A carrier retransmits a notification until it is told the message was handled.
 * Responding with a `m-notifyresp-ind` (after a notification) or an
 * `m-acknowledge-ind` (after a retrieve-conf) closes the transaction. Both are
 * POSTed to the same MMSC the send path uses, through the same [MmsPoster] seam,
 * so they ride the same network caveats and stay unit-testable off-device.
 */
class MmsAcknowledger(
    private val poster: MmsPoster,
    private val mmscResolver: MmscUrlResolver,
) {

    /**
     * Tells the MMSC the notification's message was retrieved, or will be.
     *
     * The search of statuses matters: `RETRIEVED` stops any retransmit once the
     * body is on disk, whereas `DEFERRED` promises the device will pull the
     * message later (the manual download in the thread).
     */
    fun notifyResponse(transactionId: String?, status: Int) {
        val id = transactionId ?: return
        post(MmsPduBuilder.notifyRespInd(id, status))
    }

    /** Closes the retrieve-conf transaction after its body was stored. */
    fun acknowledge(transactionId: String?) {
        val id = transactionId ?: return
        post(MmsPduBuilder.acknowledgeInd(id))
    }

    private fun post(pdu: ByteArray) {
        val mmsc = mmscResolver.mmscUrl() ?: return
        try {
            if (poster.isSuccess(poster.post(mmsc, pdu))) {
                Log.d(LogTags.RECEIVE, "Ack PDU accepted by MMSC")
            } else {
                Log.w(LogTags.RECEIVE, "MMSC rejected ack PDU")
            }
        } catch (throwable: Throwable) {
            Log.w(LogTags.RECEIVE, "Could not acknowledge MMS", throwable)
        }
    }
}