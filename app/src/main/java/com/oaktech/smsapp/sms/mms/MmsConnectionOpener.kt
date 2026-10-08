package com.oaktech.smsapp.sms.mms

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.oaktech.smsapp.sms.receive.LogTags
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Opens one connection to the MMSC.
 *
 * The MMSC lives on the carrier's own APN, which Android only routes when a
 * network advertising [NetworkCapabilities.NET_CAPABILITY_MMS] has been asked
 * for and held; on a device with a working data connection the default route
 * usually cannot reach it at all, which is why a send that looks correct on
 * Wi-Fi fails on cellular or the other way round.
 */
interface MmsConnectionOpener {

    /**
     * Opens [url] and hands the connection to [block].
     *
     * The network behind it is held only until [block] returns, so a caller
     * must not keep or reopen the connection past that point: the bearer costs
     * the user money for as long as it is up.
     */
    fun <T> open(url: String, block: (HttpURLConnection) -> T): T
}

/**
 * Rides whatever route the platform would pick anyway.
 *
 * The default in tests and the fallback in production: a request that cannot be
 * bound to an MMS network still has to be attempted, because carriers that
 * tunnel MMS over the ordinary data network (and emulators, and Wi-Fi) would
 * otherwise see no traffic at all.
 */
class DefaultRouteMmsConnectionOpener : MmsConnectionOpener {
    override fun <T> open(url: String, block: (HttpURLConnection) -> T): T =
        block(URL(url).openConnection() as HttpURLConnection)
}

/**
 * Asks the carrier for the MMS bearer, runs the request on it, lets it go.
 *
 * One lease per request rather than one held for the process: openers are
 * shared between the send, retrieve and acknowledge legs, which can run
 * concurrently, and a shared callback would let one transaction unregister the
 * network out from under another.
 *
 * The wait is bounded by [LEASE_TIMEOUT_MILLIS]: a device that never answers
 * (data off, no SIM, a Wi-Fi-only tablet) must not stall every MMS transaction
 * until the caller's own HTTP timeout. A device with no cellular radio at all
 * is skipped outright rather than waited out, since it will never produce an
 * MMS bearer.
 */
class PlatformMmsConnectionOpener(context: Context) : MmsConnectionOpener {

    private val connectivityManager: ConnectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun <T> open(url: String, block: (HttpURLConnection) -> T): T {
        val lease = MmsNetworkLease(connectivityManager)
        try {
            val network = lease.acquire()
            val connection = (
                network?.openConnection(URL(url)) ?: URL(url).openConnection()
                ) as HttpURLConnection
            return try {
                block(connection)
            } finally {
                connection.disconnect()
            }
        } finally {
            lease.release()
        }
    }

    private class MmsNetworkLease(private val connectivityManager: ConnectivityManager) {

        private val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available.set(network)
                arrived.countDown()
            }

            override fun onUnavailable() {
                arrived.countDown()
            }
        }
        private val arrived = CountDownLatch(1)
        private val available = AtomicReference<Network?>(null)

        fun acquire(): Network? {
            if (!hasCellularRadio()) return null

            return try {
                connectivityManager.requestNetwork(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_MMS)
                        .build(),
                    callback,
                    LEASE_TIMEOUT_MILLIS,
                )
                arrived.await(LEASE_TIMEOUT_MILLIS.toLong() + 1_000, TimeUnit.MILLISECONDS)
                available.get()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (throwable: Throwable) {
                Log.w(LogTags.RECEIVE, "Could not request the MMS network", throwable)
                null
            }
        }

        fun release() {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }

        private fun hasCellularRadio(): Boolean = connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }
    }

    private companion object {
        const val LEASE_TIMEOUT_MILLIS = 10_000
    }
}
