package com.oaktech.smsapp.testing

import com.oaktech.smsapp.data.telephony.ProviderMms
import com.oaktech.smsapp.data.telephony.ProviderRowRef
import com.oaktech.smsapp.data.telephony.TelephonyMessageWriter
import com.oaktech.smsapp.sms.receive.InboundMessage

/**
 * Records every provider write instead of performing one, so a test can assert
 * what the app would have put into the system store.
 *
 * [refuseInserts] stands in for a lost role: the platform's answer to a
 * non-default app is a refused write, which the real writer reports as null.
 * The fake hands out row ids from 100 up, which is as good a provider id as
 * any until a test asserts on the exact value.
 */
class RecordingTelephonyMessageWriter : TelephonyMessageWriter {

    data class SentRow(val address: String, val body: String, val sentAt: Long)

    val deleted = mutableListOf<ProviderRowRef>()
    val markedRead = mutableListOf<ProviderRowRef>()
    val markedUnread = mutableListOf<ProviderRowRef>()
    val insertedInbound = mutableListOf<InboundMessage>()
    val insertedSent = mutableListOf<SentRow>()
    val insertedInboundMms = mutableListOf<ProviderMms>()
    val insertedSentMms = mutableListOf<ProviderMms>()
    val markedFailed = mutableListOf<ProviderRowRef>()

    var refuseInserts = false

    private var nextId = 100L

    override suspend fun delete(rows: List<ProviderRowRef>) {
        deleted += rows
    }

    override suspend fun markRead(rows: List<ProviderRowRef>) {
        markedRead += rows
    }

    override suspend fun markUnread(rows: List<ProviderRowRef>) {
        markedUnread += rows
    }

    override suspend fun insertInbound(message: InboundMessage): Long? {
        if (refuseInserts) return null
        insertedInbound += message
        return nextId++
    }

    override suspend fun insertSent(address: String, body: String, sentAt: Long): Long? {
        if (refuseInserts) return null
        insertedSent += SentRow(address, body, sentAt)
        return nextId++
    }

    override suspend fun insertInboundMms(message: ProviderMms): Long? {
        if (refuseInserts) return null
        insertedInboundMms += message
        return nextId++
    }

    override suspend fun insertSentMms(message: ProviderMms): Long? {
        if (refuseInserts) return null
        insertedSentMms += message
        return nextId++
    }

    override suspend fun markSentFailed(row: ProviderRowRef) {
        markedFailed += row
    }
}
