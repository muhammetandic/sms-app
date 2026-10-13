package com.anttech.smsapp.notify

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Dismissing a conversation's alert.
 *
 * The id is derived from the conversation id the same way the notifier
 * derives it when posting, so cancelling has to reproduce that derivation
 * exactly; a mismatch would leave the alert on screen while the app shows
 * the conversation as read - the bug this guards against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationCancelTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun post(conversationId: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(context, NotificationChannels.INCOMING_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Title")
            .setContentText("Text")
            .build()
        manager.notify(NotificationIds.forConversation(conversationId), notification)
    }

    private fun activeAlertCount(): Int =
        shadowOf(context.getSystemService(NotificationManager::class.java)).activeNotifications.size

    @Test
    fun `cancelConversationNotification dismisses the posted alert`() {
        post(conversationId = 42L)
        assertEquals(1, activeAlertCount())

        cancelConversationNotification(context, conversationId = 42L)

        assertEquals("the alert is gone", 0, activeAlertCount())
    }

    @Test
    fun `cancelling a conversation with no alert is a no-op`() {
        post(conversationId = 42L)
        assertEquals(1, activeAlertCount())

        cancelConversationNotification(context, conversationId = 99L)

        assertEquals("only the matching alert is dismissed", 1, activeAlertCount())
    }

    @Test
    fun `every conversation id maps to its own alert`() {
        post(conversationId = 1L)
        post(conversationId = 2L)
        assertEquals(2, activeAlertCount())

        cancelConversationNotification(context, conversationId = 1L)

        val remaining = shadowOf(context.getSystemService(NotificationManager::class.java))
            .activeNotifications
        assertEquals(1, remaining.size)
        assertTrue("the other conversation's alert survives", remaining.isNotEmpty())
    }
}
