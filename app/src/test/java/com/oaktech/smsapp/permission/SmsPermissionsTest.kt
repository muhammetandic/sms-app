package com.oaktech.smsapp.permission

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowApplication

/**
 * The permission set is load-bearing for the receive path: a permission that is
 * declared but never requested behaves exactly like a broadcast the platform
 * chose not to send, so the SMS receiver would sit idle with nothing in the
 * logs to say why.
 */
@RunWith(RobolectricTestRunner::class)
class SmsPermissionsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `wap push stays essential for the sms role receiver`() {
        // The app does not process MMS, but ROLE_SMS requires a WAP_PUSH_DELIVER
        // receiver and the platform can drop that broadcast without the
        // permission. RECEIVE_MMS, by contrast, guards nothing here.
        assertTrue(SmsPermissions.essential.contains(Manifest.permission.RECEIVE_WAP_PUSH))
        assertFalse(SmsPermissions.essential.contains(Manifest.permission.RECEIVE_MMS))
    }

    @Test
    fun `every permission the role revokes is marked as role dependent`() {
        assertTrue(SmsPermissions.requiresSmsRole(Manifest.permission.READ_SMS))
        assertTrue(SmsPermissions.requiresSmsRole(Manifest.permission.RECEIVE_SMS))
        assertTrue(SmsPermissions.requiresSmsRole(Manifest.permission.SEND_SMS))
        assertTrue(SmsPermissions.requiresSmsRole(Manifest.permission.RECEIVE_WAP_PUSH))

        assertFalse(SmsPermissions.requiresSmsRole(Manifest.permission.READ_CONTACTS))
        assertFalse(SmsPermissions.requiresSmsRole(Manifest.permission.READ_PHONE_STATE))
        assertFalse(SmsPermissions.requiresSmsRole(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun `one denied essential permission is enough to be not all granted`() {
        val shadow = ShadowApplication.getInstance()
        shadow.grantPermissions(*SmsPermissions.essential.toTypedArray())
        shadow.denyPermissions(Manifest.permission.RECEIVE_SMS)
        assertFalse(SmsPermissions.allGranted(context))

        shadow.grantPermissions(Manifest.permission.RECEIVE_SMS)
        assertTrue(SmsPermissions.allGranted(context))
    }
}
