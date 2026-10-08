package com.oaktech.smsapp.sms.role

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Wraps the SMS role so callers do not deal with API levels or intent building.
 *
 * Holding the role is what unlocks writing to the Telephony provider and
 * receiving SMS_DELIVER. Losing it revokes SMS permissions immediately, so the
 * state is read live rather than cached.
 */
class SmsRoleManager(context: Context) {

    private val appContext = context.applicationContext

    val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isHeld(): Boolean =
        isAvailable && roleManager()?.isRoleHeld(RoleManager.ROLE_SMS) == true

    fun isQualified(): Boolean =
        isAvailable && roleManager()?.isRoleAvailable(RoleManager.ROLE_SMS) == true

    fun createRequestIntent(): Intent? =
        roleManager()?.createRequestRoleIntent(RoleManager.ROLE_SMS)

    private fun roleManager(): RoleManager? =
        if (isAvailable) appContext.getSystemService(RoleManager::class.java) else null
}