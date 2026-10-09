package com.oaktech.smsapp.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.oaktech.smsapp.sms.receive.InboundMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The shape of the notification: three distinct buttons, each bound to a
 * broadcast that names the conversation and the message it came from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IncomingMessageNotifierTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    private val message = InboundMessage(
        address = "+15551234567",
        body = "buy now",
        timestampMillis = 1_700_000_000_000L,
        parts = 1,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // POST_NOTIFICATIONS is a runtime permission from API 33, and the
        // notifier's real guard checks it before doing anything.
        shadowOf(context as Application).grantPermissions(
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        manager = context.getSystemService(NotificationManager::class.java)
    }

    private fun posted(conversationId: Long = 3L): Notification =
        requireNotNull(shadowOf(manager).getNotification(NotificationIds.forConversation(conversationId))) {
            "no notification posted for conversation $conversationId"
        }

    private fun actions(conversationId: Long = 3L): List<Notification.Action> =
        posted(conversationId).actions.orEmpty().toList()

    private fun actionIntent(index: Int, conversationId: Long = 3L): Intent {
        val pending = actions(conversationId)[index].actionIntent
        return requireNotNull(shadowOf(pending).savedIntent) { "action $index carried no intent" }
    }

    @Test
    fun `notification carries the four actions`() {
        IncomingMessageNotifier(context).notify(message, messageId = 7L, conversationId = 3L)

        assertEquals(4, actions().size)
        assertEquals(
            listOf("Reply", "Read", "Spam", "Delete"),
            actions().map { it.title.toString() },
        )
    }

    @Test
    fun `reply is a remote input that names the sender`() {
        IncomingMessageNotifier(context).notify(message, messageId = 7L, conversationId = 3L)

        val intent = actionIntent(0)
        assertEquals(
            NotificationActionReceiver.ACTION_REPLY,
            intent.getStringExtra(NotificationActionReceiver.EXTRA_ACTION),
        )
        assertEquals(
            "+15551234567",
            intent.getStringExtra(NotificationActionReceiver.EXTRA_REPLY_RECIPIENT),
        )
        // The reply arrives through the notification's remote input slot, which
        // the action must advertise for the system to render a reply field.
        assertTrue(actions(conversationId = 3L)[0].remoteInputs.size == 1)
    }

    @Test
    fun `mark read action names the conversation and message`() {
        IncomingMessageNotifier(context).notify(message, messageId = 7L, conversationId = 3L)

        val intent = actionIntent(1)
        assertEquals(
            NotificationActionReceiver::class.java.name,
            intent.component?.className,
        )
        assertEquals(
            NotificationActionReceiver.ACTION_MARK_READ,
            intent.getStringExtra(NotificationActionReceiver.EXTRA_ACTION),
        )
        assertEquals(3L, intent.getLongExtra(NotificationActionReceiver.EXTRA_CONVERSATION_ID, -1L))
        assertEquals(7L, intent.getLongExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, -1L))
    }

    @Test
    fun `spam and delete actions name the conversation and message`() {
        IncomingMessageNotifier(context).notify(message, messageId = 7L, conversationId = 3L)

        val spam = actionIntent(2)
        assertEquals(
            NotificationActionReceiver.ACTION_MARK_SPAM,
            spam.getStringExtra(NotificationActionReceiver.EXTRA_ACTION),
        )
        assertEquals(3L, spam.getLongExtra(NotificationActionReceiver.EXTRA_CONVERSATION_ID, -1L))
        assertEquals(7L, spam.getLongExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, -1L))

        val delete = actionIntent(3)
        assertEquals(
            NotificationActionReceiver.ACTION_DELETE,
            delete.getStringExtra(NotificationActionReceiver.EXTRA_ACTION),
        )
        assertEquals(3L, delete.getLongExtra(NotificationActionReceiver.EXTRA_CONVERSATION_ID, -1L))
        assertEquals(7L, delete.getLongExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, -1L))
    }

    /**
     * `PendingIntent` equality ignores extras, so buttons sharing a request code
     * would collapse into one intent and the last action built would be the only
     * one that fired.
     */
    /**
     * `PendingIntent` identity ignores extras, so two buttons sharing a request
     * code collapse into one intent and the last action built is the only one
     * that ever fires. Distinguishing them by the recorded intent is the only
     * way to catch that from a test.
     */
    @Test
    fun `the four actions resolve to distinct pending intents`() {
        IncomingMessageNotifier(context).notify(message, messageId = 7L, conversationId = 3L)

        val actions = actions()
        assertEquals(4, actions.size)
        for (i in actions.indices) {
            for (j in i + 1 until actions.size) {
                assertTrue(
                    "actions $i and $j resolved to the same intent",
                    actions[i].actionIntent != actions[j].actionIntent,
                )
                assertTrue(
                    "actions $i and $j share a request code",
                    shadowOf(actions[i].actionIntent).requestCode !=
                        shadowOf(actions[j].actionIntent).requestCode,
                )
            }
        }
    }

    /**
     * The notification id is derived from the conversation, so two conversations
     * are two notifications. If the ids collided, the second `notify` would
     * silently replace the first and this would see one notification.
     */
    @Test
    fun `conversations get separate notifications`() {
        IncomingMessageNotifier(context).notify(message, messageId = 7L, conversationId = 3L)
        IncomingMessageNotifier(context).notify(message, messageId = 9L, conversationId = 4L)

        assertEquals(2, shadowOf(manager).size())
        assertEquals(7L, actionIntent(0, conversationId = 3L)
            .getLongExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, -1L))
        assertEquals(9L, actionIntent(0, conversationId = 4L)
            .getLongExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, -1L))
    }

    @Test
    fun `conversation ids 1000 apart still get separate notifications`() {
        IncomingMessageNotifier(context).notify(message, messageId = 7L, conversationId = 3L)
        IncomingMessageNotifier(context).notify(message, messageId = 9L, conversationId = 1003L)

        // The old modulo-based ids collapsed these into one slot; the row-id slot
        // keeps them apart.
        assertEquals(2, shadowOf(manager).size())
        assertEquals(3L, actionIntent(0, conversationId = 3L)
            .getLongExtra(NotificationActionReceiver.EXTRA_CONVERSATION_ID, -1L))
        assertEquals(1003L, actionIntent(0, conversationId = 1003L)
            .getLongExtra(NotificationActionReceiver.EXTRA_CONVERSATION_ID, -1L))
    }

    @Test
    fun `notification id is the shared conversation scheme`() {
        IncomingMessageNotifier(context).notify(message, messageId = 7L, conversationId = 3L)

        assertEquals(1, shadowOf(manager).size())
        assertEquals(1, shadowOf(manager).getAllNotifications().size)
    }

    @Test
    fun `tap target still opens the thread`() {
        IncomingMessageNotifier(context).notify(message, messageId = 7L, conversationId = 3L)

        val content = shadowOf(posted().contentIntent).savedIntent!!
        assertEquals(3L, content.getLongExtra("com.oaktech.smsapp.extra.CONVERSATION_ID", -1L))
    }

    /**
     * The caller resolves the sender's display name; the notification has to
     * show the name the user knows rather than the raw number.
     */
    @Test
    fun `title shows the resolved contact name`() {
        IncomingMessageNotifier(context).notify(
            message,
            messageId = 7L,
            conversationId = 3L,
            displayName = "Alice Example",
        )

        assertEquals("Alice Example", posted().extras.getString(Notification.EXTRA_TITLE))
    }

    /**
     * No contact (or a role loss that took READ_CONTACTS with it) must not leave
     * a blank title, so the number is the fallback.
     */
    @Test
    fun `title falls back to the number without a contact`() {
        IncomingMessageNotifier(context).notify(message, messageId = 7L, conversationId = 3L)

        assertEquals("+15551234567", posted().extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `blank display name falls back to the number`() {
        IncomingMessageNotifier(context).notify(
            message,
            messageId = 7L,
            conversationId = 3L,
            displayName = "   ",
        )

        assertEquals("+15551234567", posted().extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `mms title shows the resolved contact name`() {
        IncomingMessageNotifier(context).notifyMms(
            address = "+15551234567",
            body = "",
            subject = null,
            messageId = 7L,
            conversationId = 3L,
            attachmentCount = 1,
            displayName = "Alice Example",
        )

        assertEquals("Alice Example", posted().extras.getString(Notification.EXTRA_TITLE))
    }
}
