package com.anttech.smsapp.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.anttech.smsapp.R
import com.anttech.smsapp.sms.receive.InboundMessage
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Every alert carries the app's own small icon.
 *
 * The small icon is the one part of a notification the system draws as a
 * monochrome silhouette tinted with the accent, so a placeholder platform icon
 * (the chat bubble this replaced) makes the app look like an unknown sender on
 * the shade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationIconTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun smallIconResId(id: Int): Int {
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).activeNotifications.single { it.id == id }
        return posted.notification.smallIcon.resId
    }

    @Test
    fun `an incoming message uses the app's small icon`() {
        IncomingMessageNotifier(context).notify(
            message = InboundMessage("+15551234567", "hello", 1_700_000_000_000L, parts = 1),
            messageId = 1L,
            conversationId = 7L,
        )

        assertEquals(
            R.drawable.ic_notification,
            smallIconResId(NotificationIds.forConversation(7L)),
        )
    }

    @Test
    fun `a send failure uses the app's small icon`() {
        OutgoingFailureNotifier(context).notify(recipient = "+15551234567", conversationId = 7L)

        assertEquals(
            R.drawable.ic_notification,
            smallIconResId(NotificationIds.forFailedSend(7L)),
        )
    }
}
