package com.oaktech.smsapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.oaktech.smsapp.data.DataGraph
import com.oaktech.smsapp.data.telephony.ProviderChangeObserver
import com.oaktech.smsapp.ui.ComposeRequest
import com.oaktech.smsapp.ui.SmsApp
import com.oaktech.smsapp.ui.compose.ComposeIntentParser
import com.oaktech.smsapp.ui.theme.SmsAppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * Where the app should open. Held as state rather than read once from
     * `getIntent`, so a configuration change does not lose a pending deep link
     * and a second SENDTO intent is not ignored.
     */
    private var launchTarget by mutableStateOf<LaunchTarget?>(null)

    private lateinit var providerChangeObserver: ProviderChangeObserver
    private val providerChangeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        launchTarget = savedInstanceState?.readLaunchTarget() ?: intent.toLaunchTarget()

        // Two startup chores that must not block the first frame. A message can
        // be persisted as SENDING, then have its send result delivered to a dead
        // process; nothing will ever resolve it, so clear the stale state rather
        // than showing a permanent spinner. The provider import then backfills
        // history the user expects to see.
        lifecycleScope.launch {
            val graph = DataGraph.get(this@MainActivity)
            graph.messageRepository.resolveStuckOutgoing()
            graph.providerImport.importIfPossible()
        }

        // Keep the store in step with rows other apps or the platform write to
        // the provider while this app is open, not just backfilled at launch.
        providerChangeObserver = ProviderChangeObserver(
            context = this,
            onProviderChanged = {
                DataGraph.get(this@MainActivity).providerImport.importIfPossible()
            },
            scope = providerChangeScope,
        ).also { it.start() }

        setContent {
            SmsAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SmsApp(
                        initialConversationId = launchTarget?.conversationId,
                        composeRequest = launchTarget?.composeRequest,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        launchTarget?.conversationId?.let { outState.putLong(STATE_CONVERSATION_ID, it) }
        launchTarget?.composeRequest?.let { request ->
            outState.putString(STATE_COMPOSE_RECIPIENT, request.recipient)
            outState.putString(STATE_COMPOSE_BODY, request.body)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTop means a second SENDTO arrives here rather than creating a new
        // instance, so the target has to be re-read or the request is dropped.
        intent.toLaunchTarget()?.let { launchTarget = it }
    }

    override fun onDestroy() {
        providerChangeObserver.stop()
        providerChangeScope.cancel()
        super.onDestroy()
    }

    private fun Intent?.toLaunchTarget(): LaunchTarget? {
        if (this == null) return null

        val conversationId = getLongExtra(EXTRA_CONVERSATION_ID, -1L).takeIf { it > 0 }

        // Only a SENDTO action carries a recipient. Reading the URI on every
        // launch would turn a plain launcher start into a compose request.
        if (action == Intent.ACTION_SENDTO) {
            val recipients = ComposeIntentParser.recipientsFrom(dataString)
            // The body can arrive as a query parameter (?body=), as EXTRA_TEXT,
            // or as the legacy "sms_body" extra. Check all three sources.
            val body = ComposeIntentParser.bodyFrom(dataString)
                ?: getStringExtra(Intent.EXTRA_TEXT)
                ?: getStringExtra("sms_body")
                ?: ""
            return LaunchTarget(
                composeRequest = ComposeRequest(
                    // A SENDTO may name several recipients ("sms:+111,+222").
                    // The composer models them as one comma-joined address and
                    // the send path sends each its own message, so every
                    // recipient is kept rather than silently dropping all but
                    // the first.
                    recipient = recipients.joinToString(", "),
                    body = body,
                ),
            )
        }

        // ACTION_SEND from a share sheet carries text but no recipient.
        if (action == Intent.ACTION_SEND && type == "text/plain") {
            val body = getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            return LaunchTarget(
                composeRequest = ComposeRequest(recipient = "", body = body),
            )
        }

        return conversationId?.let { LaunchTarget(conversationId = it) }
    }

    private fun Bundle.readLaunchTarget(): LaunchTarget? {
        val conversationId = getLong(STATE_CONVERSATION_ID, -1L).takeIf { it > 0 }
        val recipient = getString(STATE_COMPOSE_RECIPIENT)
        val body = getString(STATE_COMPOSE_BODY)

        return when {
            recipient != null -> LaunchTarget(composeRequest = ComposeRequest(recipient, body.orEmpty()))
            conversationId != null -> LaunchTarget(conversationId = conversationId)
            else -> null
        }
    }

    private companion object {
        const val STATE_CONVERSATION_ID = "com.oaktech.smsapp.extra.STATE_CONVERSATION_ID"
        const val STATE_COMPOSE_RECIPIENT = "com.oaktech.smsapp.extra.STATE_COMPOSE_RECIPIENT"
        const val STATE_COMPOSE_BODY = "com.oaktech.smsapp.extra.STATE_COMPOSE_BODY"
    }
}

/** Where the app should open, from a notification tap or a SENDTO request. */
private data class LaunchTarget(
    val conversationId: Long? = null,
    val composeRequest: ComposeRequest? = null,
)

const val EXTRA_CONVERSATION_ID = "com.oaktech.smsapp.extra.CONVERSATION_ID"