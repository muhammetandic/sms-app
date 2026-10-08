package com.oaktech.smsapp.sms.send

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmsSubscriptionSettingsTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `defaults to following the system subscription`() {
        assertNull(SmsSubscriptionSettings(context).preferredSubscriptionId())
    }

    @Test
    fun `a chosen subscription is persisted and read back`() {
        SmsSubscriptionSettings(context).setPreferredSubscriptionId(5)

        assertEquals(5, SmsSubscriptionSettings(context).preferredSubscriptionId())
    }

    @Test
    fun `clearing restores the system default`() {
        val settings = SmsSubscriptionSettings(context)
        settings.setPreferredSubscriptionId(5)
        settings.setPreferredSubscriptionId(null)

        assertNull(SmsSubscriptionSettings(context).preferredSubscriptionId())
    }

    @Test
    fun `an invalid subscription id is never stored`() {
        val settings = SmsSubscriptionSettings(context)
        settings.setPreferredSubscriptionId(0)
        settings.setPreferredSubscriptionId(-1)

        assertNull(SmsSubscriptionSettings(context).preferredSubscriptionId())
    }

    @Test
    fun `a new choice replaces the previous one`() {
        val settings = SmsSubscriptionSettings(context)
        settings.setPreferredSubscriptionId(5)
        settings.setPreferredSubscriptionId(6)

        assertEquals(6, SmsSubscriptionSettings(context).preferredSubscriptionId())
    }
}