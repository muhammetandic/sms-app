package com.oaktech.smsapp.sms.role

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent

/**
 * Wraps the SMS role so callers do not deal with API levels or intent building.
 *
 * Holding the role is what unlocks writing to the Telephony provider and
 * receiving SMS_DELIVER. Losing it revokes SMS permissions immediately, so the
 * state is read live rather than cached.
 */
class SmsRoleManager(context: Context) {

    private val appContext = context.applicationContext

    fun isHeld(): Boolean =
        roleManager()?.isRoleHeld(RoleManager.ROLE_SMS) == true

    fun isQualified(): Boolean =
        roleManager()?.isRoleAvailable(RoleManager.ROLE_SMS) == true

    fun createRequestIntent(): Intent? =
        roleManager()?.createRequestRoleIntent(RoleManager.ROLE_SMS)

    // RoleManager landed in API 29, below the minSdk 30 floor, so the old
    // version gate is gone; the service can still resolve to null.
    private fun roleManager(): RoleManager? =
        appContext.getSystemService(RoleManager::class.java)
}
