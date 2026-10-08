package com.oaktech.smsapp.sms.send

import android.content.Context
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.sms.mms.HttpMmsPoster
import com.oaktech.smsapp.sms.mms.HttpMmsRetriever
import com.oaktech.smsapp.sms.mms.MmsAcknowledger
import com.oaktech.smsapp.sms.mms.MmsAutoRetriever
import com.oaktech.smsapp.sms.mms.MmsDownloader
import com.oaktech.smsapp.sms.mms.MmsPartSource
import com.oaktech.smsapp.sms.mms.MmsSendCoordinator
import com.oaktech.smsapp.sms.mms.MmscUrlResolver
import com.oaktech.smsapp.sms.mms.PlatformApnResolver
import com.oaktech.smsapp.sms.mms.PlatformMmsConnectionOpener
import com.oaktech.smsapp.sms.mms.PlatformMmsPartSource

/**
 * The two gates onto the MMS send path: the coordinator that does the MMSC
 * transaction and the sender that decides when a message belongs on it.
 * Wired here so call sites stay one-liners.
 */
object MmsSendGraph {

    @Volatile
    private var sender: RouteAwareMessageSender? = null

    @Volatile
    private var downloader: MmsDownloader? = null

    @Volatile
    private var partSource: MmsPartSource? = null

    fun sender(context: Context): RouteAwareMessageSender {
        val existing = sender
        if (existing != null) return existing
        return synchronized(this) {
            sender ?: RouteAwareMessageSender(
                sms = { recipient, body -> SendGraph.coordinator(context).send(recipient, body) },
                mms = coordinator(context),
            ).also { sender = it }
        }
    }

    fun coordinator(context: Context): MmsSendCoordinator =
        MmsSendCoordinator(
            poster = poster(context),
            mmscResolver = resolver(context),
            context = context,
            isRoleHeld = { DataGraph.get(context).roleManager.isHeld() },
        )

    fun partSource(context: Context): MmsPartSource {
        val existing = partSource
        if (existing != null) return existing
        return synchronized(this) {
            partSource ?: PlatformMmsPartSource(context).also { partSource = it }
        }
    }

    fun downloader(context: Context): MmsDownloader {
        val existing = downloader
        if (existing != null) return existing
        return synchronized(this) {
            downloader ?: MmsDownloader(
                context = context,
                partSource = partSource(context),
                autoRetriever = MmsAutoRetriever(HttpMmsRetriever(opener(context)), resolver(context)),
                acknowledger = MmsAcknowledger(poster(context), resolver(context)),
            ).also { downloader = it }
        }
    }

    private fun resolver(context: Context): MmscUrlResolver = PlatformApnResolver(context)

    /** Fresh per call: the opener holds no state between transactions. */
    private fun opener(context: Context) = PlatformMmsConnectionOpener(context)

    private fun poster(context: Context) = HttpMmsPoster(opener(context))
}