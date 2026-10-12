package com.anttech.smsapp.data.telephony

import com.anttech.smsapp.data.local.MessageDirection
import com.anttech.smsapp.data.local.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMessageMapperTest {

    private fun providerMessage(
        id: Long = 1L,
        address: String = "+15551234567",
        body: String = "hello",
        date: Long = 1_000L,
        type: Int = SmsProviderTypes.MESSAGE_TYPE_INBOX,
        status: Int = SmsProviderTypes.STATUS_NONE,
    ) = ProviderMessage(
        id = id,
        threadId = 1L,
        address = address,
        body = body,
        date = date,
        type = type,
        status = status,
        read = true,
    )

    @Test
    fun `an inbox message is incoming and delivered`() {
        val mapped = ProviderMessageMapper.map(providerMessage())!!

        assertEquals(MessageDirection.INCOMING, mapped.direction)
        assertEquals(MessageStatus.DELIVERED, mapped.status)
    }

    @Test
    fun `a sent message is outgoing and delivered`() {
        val mapped = ProviderMessageMapper.map(
            providerMessage(
                type = SmsProviderTypes.MESSAGE_TYPE_SENT,
                status = SmsProviderTypes.STATUS_COMPLETE,
            ),
        )!!

        assertEquals(MessageDirection.OUTGOING, mapped.direction)
        assertEquals(MessageStatus.DELIVERED, mapped.status)
    }

    @Test
    fun `a pending send is reported as sent not stuck`() {
        // STATUS_PENDING is a 0-100 percentage, not an enum member, so only
        // STATUS_FAILED means failure.
        val mapped = ProviderMessageMapper.map(
            providerMessage(
                type = SmsProviderTypes.MESSAGE_TYPE_OUTBOX,
                status = SmsProviderTypes.STATUS_PENDING,
            ),
        )!!

        assertEquals(MessageStatus.SENT, mapped.status)
    }

    @Test
    fun `a failed send is reported as failed`() {
        val mapped = ProviderMessageMapper.map(
            providerMessage(
                type = SmsProviderTypes.MESSAGE_TYPE_SENT,
                status = SmsProviderTypes.STATUS_FAILED,
            ),
        )!!

        assertEquals(MessageStatus.FAILED, mapped.status)
    }

    @Test
    fun `a failed outbox type is failed even without a failed status`() {
        val mapped = ProviderMessageMapper.map(
            providerMessage(
                type = SmsProviderTypes.MESSAGE_TYPE_FAILED,
                status = SmsProviderTypes.STATUS_NONE,
            ),
        )!!

        assertEquals(MessageStatus.FAILED, mapped.status)
    }

    @Test
    fun `a row with no address is skipped rather than filed somewhere arbitrary`() {
        assertNull(ProviderMessageMapper.map(providerMessage(address = "")))
        assertNull(ProviderMessageMapper.map(providerMessage(address = "   ")))
    }

    @Test
    fun `a row with neither address nor body is skipped`() {
        assertNull(ProviderMessageMapper.map(providerMessage(address = "", body = "")))
    }

    @Test
    fun `an address with only whitespace is skipped`() {
        assertNull(ProviderMessageMapper.map(providerMessage(address = "\t ")))
    }

    @Test
    fun `the address is trimmed`() {
        val mapped = ProviderMessageMapper.map(providerMessage(address = "  +15551234567  "))!!

        assertEquals("+15551234567", mapped.address)
    }

    @Test
    fun `the provider id is carried through`() {
        assertEquals(42L, ProviderMessageMapper.map(providerMessage(id = 42L))!!.providerId)
    }

    @Test
    fun `the dedupe key is namespaced to the provider`() {
        // The live receive path keys on address/timestamp/body, so an unprefixed
        // key would collide with a message already stored by SMS_DELIVER and
        // silently drop the import.
        val key = ProviderMessageMapper.dedupeKey(7L)

        assertEquals("provider:7", key)
        assertNotEquals("7", key)
    }

    @Test
    fun `different provider ids produce different dedupe keys`() {
        assertNotEquals(
            ProviderMessageMapper.dedupeKey(1L),
            ProviderMessageMapper.dedupeKey(2L),
        )
    }

    @Test
    fun `identical provider rows map to identical dedupe keys`() {
        val first = ProviderMessageMapper.dedupeKey(5L)
        val second = ProviderMessageMapper.dedupeKey(5L)

        assertEquals(first, second)
    }

    @Test
    fun `an empty body is still a real message`() {
        // Some carriers store a zero-length body for a message carried as an
        // attachment stub; dropping it loses the fact that contact messaged.
        val mapped = ProviderMessageMapper.map(providerMessage(body = ""))!!

        assertEquals("", mapped.body)
    }

    @Test
    fun `the conversation key matches the live receive path`() {
        val mapped = ProviderMessageMapper.map(providerMessage())!!

        assertEquals(
            ProviderMessageMapper.conversationKeyFor(mapped.address),
            ProviderMessageMapper.conversationKeyFor("+1 555 123 4567"),
        )
    }
}
