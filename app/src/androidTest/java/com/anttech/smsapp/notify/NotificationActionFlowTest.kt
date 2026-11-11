package com.anttech.smsapp.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput as PlatformRemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anttech.smsapp.R
import com.anttech.smsapp.data.DataGraph
import com.anttech.smsapp.data.MessageRepository
import com.anttech.smsapp.data.local.SmsDatabase
import com.anttech.smsapp.sms.receive.InboundMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fires the quick actions of a real posted notification through the real
 * `PendingIntent` layer, the way SystemUI does, against an injected
 * in-memory store.
 *
 * The unit tests exercise the receiver with hand-built intents, which cannot
 * regress the contracts this flow leans on: the notification id both sides
 * derive, the request codes that keep the actions apart, the extras the
 * receiver guards on, and the RemoteInput delivery a direct reply rides on.
 * Those only hold when the actual notification is posted and its actual
 * action intents are sent, so the actions are pulled out of
 * `NotificationManager.activeNotifications` - the same records the shade
 * holds - rather than rebuilt by the test.
 *
 * The steps follow the reported failure's order: reply from the
 * notification first, then a fresh notification for the same conversation,
 * then its read and delete buttons - "after a reply" is exactly when those
 * were reported dead.
 *
 * The store is an in-memory Room instance swapped in through
 * `DataGraph.overrideForTest`, because the manifest receiver resolves its
 * repository through the process-wide graph and would otherwise write to
 * this install's real database. The override window is kept short: a real
 * inbound SMS landing in the in-memory store while it holds would be dropped
 * with it, and the alternative - writing the synthetic rows to the real
 * store - would risk the Drive backup sweeping them up mid-test.
 *
 * The reply targets a recipient `SendValidator` rejects, so the coordinator
 * refuses the send before any radio work while the rest of the reply branch
 * - mark read, cancel the notification - runs for real.
 *
 * Runs on a connected device: `./gradlew :app:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=com.anttech.smsapp.notify.NotificationActionFlowTest`.
 */
@RunWith(AndroidJUnit4::class)
class NotificationActionFlowTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private lateinit var database: SmsDatabase
    private lateinit var repository: MessageRepository

    /**
     * A sender no carrier will route: `SendValidator` refuses a leading plus
     * with a single digit, so the reply branch's send is rejected up front and
     * nothing leaves the device, while the store work behind it still runs.
     */
    private val address = "+1"

    private var conversationId: Long? = null
    private lateinit var savedActionPreferences: Map<String, Boolean>

    @Before
    fun setUp() {
        // Every action under test has to be on the posted notification, which
        // the settings screen can turn off per install. The previous values are
        // restored in tearDown so the install is left exactly as it was found.
        val preferences = NotificationActionPreferences(context)
        savedActionPreferences = NotificationActionReceiver.ALL_ACTIONS.associateWith { preferences.isEnabled(it) }
        NotificationActionReceiver.ALL_ACTIONS.forEach { preferences.setEnabled(it, true) }

        // Posting is a silent no-op when the app's notifications are off, which
        // would fail every assertion below for a reason outside the code under
        // test; skip instead, the way the provider-write test skips without the
        // SMS role.
        assumeTrue(
            "notifications are disabled for this app",
            NotificationChannels.canPostNotifications(context),
        )

        database = Room.inMemoryDatabaseBuilder(context, SmsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MessageRepository(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            draftDao = database.draftDao(),
        )
        DataGraph.overrideForTest(context, repository)
    }

    @After
    fun tearDown() {
        // Let a still-running action finish before its store closes under it.
        // Bounded rather than awaitIdle() so a wedged receiver cannot hang the
        // teardown itself; the effect polls above have already failed loudly
        // by the time this can time out.
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (NotificationActionReceiver.inFlight.get() > 0 && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(10)
        }

        conversationId?.let { manager?.cancel(NotificationIds.forConversation(it)) }
        DataGraph.overrideForTest(context, null)
        database.close()

        val preferences = NotificationActionPreferences(context)
        savedActionPreferences.forEach { (action, enabled) -> preferences.setEnabled(action, enabled) }
    }

    @Test
    fun quickActionsApplyOnTheNotificationAfterAReply() = runBlocking {
        // A direct reply off the first notification, delivered the way the
        // shade delivers it: the typed text rides a fill-in intent on the
        // action's own PendingIntent.
        val (firstMessage, firstRowId) = storeIncoming(1)
        conversationId = repository.conversations.conversationIdFor(address)
        val id = requireNotNull(conversationId) { "the stored message left no conversation" }

        IncomingMessageNotifier(context).notify(firstMessage, firstRowId, id)
        val firstNotification = activeNotificationFor(id)
        assertNotNull("the notification was not posted", firstNotification)
        fireReply(requireNotNull(firstNotification).actionWithRemoteInput(), "reply from the test")

        // The reply marks the thread read, leaves no outgoing row behind the
        // refused send, and re-posts the alert in its replied state rather
        // than cancelling it - the cancel would race the closing remote-input
        // session and can leave a dead entry behind.
        // The wait is on the history itself, not on the notification merely
        // existing: the pre-reply entry is still posted while the receiver runs,
        // and the read count flips before the re-post, so either of those alone
        // would return the old entry and read a null history.
        awaitEffect("reply did not mark the conversation read and re-post its notification") {
            database.conversationDao().getById(id)?.unreadCount == 0 &&
                activeNotificationFor(id)?.extras
                    ?.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY) != null
        }
        assertEquals(
            "the refused send should not have persisted an outgoing row",
            listOf(firstRowId),
            repository.observeMessages(id).first().map { it.id },
        )
        val reposted = requireNotNull(activeNotificationFor(id)) { "the reply did not re-post the alert" }
        assertEquals(
            "the re-posted alert should carry the reply as remote-input history",
            listOf("reply from the test"),
            reposted.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
                ?.map { it.toString() },
        )

        // The reported failure: after a reply, the entry the shade holds has
        // to stay alive, so its Okundu button has to work again.
        fire(reposted.actionTitled(R.string.notification_action_read))

        awaitEffect("mark read on the re-posted notification did not dismiss it") {
            database.conversationDao().getById(id)?.unreadCount == 0 &&
                activeNotificationFor(id) == null
        }

        // A later message gets a fresh notification, and its Okundu button
        // has to work there too.
        val (secondMessage, secondRowId) = storeIncoming(2)
        assertEquals(1, database.conversationDao().getById(id)?.unreadCount)
        IncomingMessageNotifier(context).notify(secondMessage, secondRowId, id)
        val secondNotification = activeNotificationFor(id)
        assertNotNull("the follow-up notification was not posted", secondNotification)
        fire(requireNotNull(secondNotification).actionTitled(R.string.notification_action_read))

        awaitEffect("mark read did not clear the unread count and dismiss the notification") {
            database.conversationDao().getById(id)?.unreadCount == 0 &&
                activeNotificationFor(id) == null
        }

        // The same for Sil: a third message, whose notification's delete
        // button has to remove exactly the row it was posted about.
        val (thirdMessage, thirdRowId) = storeIncoming(3)
        assertEquals(1, database.conversationDao().getById(id)?.unreadCount)
        IncomingMessageNotifier(context).notify(thirdMessage, thirdRowId, id)
        val thirdNotification = activeNotificationFor(id)
        assertNotNull("the third notification was not posted", thirdNotification)
        fire(requireNotNull(thirdNotification).actionTitled(R.string.notification_action_delete))

        awaitEffect("delete did not remove the notified message and dismiss the notification") {
            database.messageDao().getById(thirdRowId) == null &&
                activeNotificationFor(id) == null
        }
        assertTrue(
            "delete should have removed only the notified message",
            repository.observeMessages(id).first().map { it.id } == listOf(firstRowId, secondRowId),
        )
    }

    /** Stores one synthetic incoming message and returns it with its row id. */
    private suspend fun storeIncoming(sequence: Int): Pair<InboundMessage, Long> {
        val message = InboundMessage(
            address = address,
            body = "notification flow test message $sequence",
            // Unique per message so the dedupe key cannot fold the inserts
            // together; the bodies differ, but the timestamp is part of the
            // key's identity and need not rely on clock granularity.
            timestampMillis = System.currentTimeMillis() + sequence * 1_000,
            parts = 1,
        )
        val rowId = repository.recordInbound(message, partCount = 1)
        return message to requireNotNull(rowId) { "the synthetic message was stored as a duplicate" }
    }

    /** The notification currently showing for a conversation, or null. */
    private fun activeNotificationFor(conversationId: Long): Notification? =
        manager?.activeNotifications
            ?.firstOrNull { it.id == NotificationIds.forConversation(conversationId) }
            ?.notification

    /** The action button carrying the direct-reply remote input. */
    private fun Notification.actionWithRemoteInput(): Notification.Action =
        actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            ?: error("the notification carries no reply action")

    /** The action button for one of the plain quick actions, found by label. */
    private fun Notification.actionTitled(labelRes: Int): Notification.Action {
        val label = context.getString(labelRes)
        return actions.firstOrNull { it.title.toString() == label }
            ?: error("the notification carries no action labelled \"$label\"")
    }

    /** Fires an action's intent the way a tap on its button does. */
    private fun fire(action: Notification.Action) {
        action.actionIntent.send(context, 0, null)
    }

    /**
     * Fires the reply action the way SystemUI does: the typed text is packed
     * into a fill-in intent as remote-input results and sent on the action's
     * own `PendingIntent`, which the platform merges with the base intent the
     * receiver reads.
     */
    private fun fireReply(action: Notification.Action, text: String) {
        val remoteInputs = requireNotNull(action.remoteInputs) { "the reply action carries no remote inputs" }
        val fillIn = Intent()
        PlatformRemoteInput.addResultsToIntent(
            remoteInputs,
            fillIn,
            Bundle().apply { putCharSequence(NotificationActionReceiver.KEY_QUICK_REPLY, text) },
        )
        action.actionIntent.send(context, 0, fillIn)
    }

    /**
     * Suspends until the effect is visible, because `PendingIntent.send`
     * returns before the receiver's coroutine has applied anything.
     * `awaitIdle` alone would race the start of the broadcast; waiting on the
     * effect itself covers both the dispatch and the store write.
     */
    private suspend fun awaitEffect(description: String, timeoutMillis: Long = 10_000, effect: suspend () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (effect()) return
            delay(20)
        }
        fail(description)
    }
}
