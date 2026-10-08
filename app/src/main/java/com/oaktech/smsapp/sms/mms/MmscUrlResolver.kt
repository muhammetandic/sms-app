package com.oaktech.smsapp.sms.mms

import android.content.Context
import android.provider.Telephony

/**
 * Resolves the MMSC that a send should target.
 *
 * The send-req is delivered to the APN's MMSC, which Android exposes through
 * `content://telephony/carriers`. ROMs and multi-SIM devices differ in which
 * carrier row is "current", so the seam keeps the coordinator pure and leaves
 * the device specifics here.
 */
interface MmscUrlResolver {
    /** The MMSC URL for the active carrier, or null when there is none. */
    fun mmscUrl(): String?
}

object NullMmscUrlResolver : MmscUrlResolver {
    override fun mmscUrl(): String? = null
}

/** Reads the MMSC of the currently-selected carrier APN. */
class PlatformApnResolver(context: Context) : MmscUrlResolver {

    private val resolver = context.applicationContext.contentResolver

    override fun mmscUrl(): String? {
        resolver.query(
            Telephony.Carriers.CONTENT_URI,
            arrayOf(Telephony.Carriers.MMSC),
            "${Telephony.Carriers.CURRENT}=1",
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(Telephony.Carriers.MMSC)
            if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) {
                return cursor.getString(column)
            }
        }
        return null
    }
}