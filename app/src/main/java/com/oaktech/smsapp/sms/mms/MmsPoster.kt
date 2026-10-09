package com.oaktech.smsapp.sms.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.oaktech.smsapp.sms.receive.LogTags
import com.oaktech.smsapp.sms.send.ResultCode
import com.oaktech.smsapp.sms.send.SmsSubscriptionSelector
import com.oaktech.smsapp.sms.send.SmsSubscriptionSettings
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one seam the MMSC transaction needs.
 *
 * MMS send is an HTTP POST of the send-req PDU to the carrier's MMSC. Splitting
 * the network client out is what makes [MmsSendCoordinator] unit-testable; the
 * real leg ([HttpMmsPoster]) is a thin HttpURLConnection.
 */
interface MmsPoster {
    /**
     * POSTs the PDU to the MMSC and returns the HTTP status. Anything but a
     * 2xx counts as a failed transaction; a throw represents no transport.
     */
    fun post(mmscUrl: String, pdu: ByteArray): Int

    /** Platform MMS submission does not need an app-resolved MMSC URL. */
    val requiresMmscUrl: Boolean
        get() = true

    /** Whether a failed transaction is safe and useful to retry in WorkManager. */
    val supportsAutomaticRetry: Boolean
        get() = true

    fun failureCode(status: Int): ResultCode = ResultCode.MMS_SEND_FAILED

    /** Whether an HTTP status is a successful MMSC transaction. */
    fun isSuccess(status: Int): Boolean = status in 200..299
}

/**
 * Posts over HttpURLConnection on the route [MmsConnectionOpener] picks, which
 * in production is the carrier's MMS bearer.
 *
 * The network binding lives in the opener rather than here so this class stays
 * a straight HTTP POST and the bearer's request/hold/release lifecycle is
 * written once for every MMSC leg (send, retrieve, acknowledge).
 */
class HttpMmsPoster(
    private val opener: MmsConnectionOpener = DefaultRouteMmsConnectionOpener(),
) : MmsPoster {

    override fun post(mmscUrl: String, pdu: ByteArray): Int = opener.open(mmscUrl) { connection ->
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", HTTP_CONTENT_TYPE)
        connection.setRequestProperty("Accept", HTTP_CONTENT_TYPE)
        connection.setConnectTimeout(TIMEOUT_MILLIS)
        connection.setReadTimeout(TIMEOUT_MILLIS)
        DataOutputStream(connection.outputStream).use { it.write(pdu) }
        connection.responseCode
    }

    companion object {
        const val HTTP_CONTENT_TYPE = "application/vnd.wap.mms-message"
        const val TIMEOUT_MILLIS = 30_000
    }
}

/**
 * Uses the platform MMS stack instead of reading the protected APN table and
 * manually opening an MMSC socket. The carrier owns proxy, bearer, retry and
 * subscription details; SmsManager is the only stable public API for that
 * contract on a normal device.
 */
class PlatformMmsPoster(context: Context) : MmsPoster {

    private val appContext = context.applicationContext
    private val subscriptionSettings = SmsSubscriptionSettings(appContext)

    override fun post(mmscUrl: String, pdu: ByteArray): Int {
        val file = File(appContext.cacheDir, "mms-send/${UUID.randomUUID()}.pdu").apply {
            parentFile?.mkdirs()
            writeBytes(pdu)
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.files",
            file,
        )
        val action = "${appContext.packageName}.MMS_SENT.${UUID.randomUUID()}"
        val result = AtomicInteger(SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        val finished = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                result.set(resultCode)
                finished.countDown()
            }
        }

        try {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            appContext.grantUriPermission(
                "com.android.phone",
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val callback = PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(action).setPackage(appContext.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            smsManager().sendMultimediaMessage(
                appContext,
                uri,
                mmscUrl,
                null,
                callback,
            )
            if (!finished.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                Log.e(LogTags.SEND, "Platform MMS send timed out")
                return SmsManager.RESULT_ERROR_NO_SERVICE
            }
            return result.get()
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
            appContext.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            file.delete()
        }
    }

    override fun isSuccess(status: Int): Boolean = status == Activity.RESULT_OK

    override val requiresMmscUrl: Boolean = false

    override val supportsAutomaticRetry: Boolean = false

    override fun failureCode(status: Int): ResultCode =
        if (status == SmsManager.RESULT_ERROR_NO_SERVICE) {
            ResultCode.MMS_NETWORK_UNAVAILABLE
        } else {
            ResultCode.MMS_SEND_FAILED
        }

    private fun smsManager(): SmsManager {
        val preferred = subscriptionSettings.preferredSubscriptionId()
        val default = runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }
            .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val subscriptionId = SmsSubscriptionSelector.effectiveSubscriptionId(preferred, default)
        return SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 60_000L
    }
}
