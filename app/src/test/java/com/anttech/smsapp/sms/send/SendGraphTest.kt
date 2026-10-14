package com.anttech.smsapp.sms.send

import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SendGraphTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        SendGraph.overrideForTest(null)
    }

    @Test
    fun `overrideForTest swaps the process-wide coordinator`() {
        val stub = SmsSendCoordinator(context, StubTransport, isRoleHeld = { false })

        SendGraph.overrideForTest(stub)

        // The service and receivers reach the coordinator only through the graph,
        // so the swap has to be visible there for a test to stand in for the radio.
        assertSame(stub, SendGraph.coordinator(context))
    }

    private object StubTransport : SmsTransport {
        override fun canSend(): Boolean = true

        override fun divideMessage(body: String): List<String> = listOf(body)

        override fun sendMultipart(
            destination: String,
            parts: List<String>,
            sentIntents: List<PendingIntent>,
            deliveryIntent: PendingIntent,
        ) = Unit

        override fun subscriptionId(): Int = 1
    }
}
