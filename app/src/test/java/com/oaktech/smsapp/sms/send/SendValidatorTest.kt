package com.oaktech.smsapp.sms.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendValidatorTest {

    @Test
    fun `valid number and body are accepted`() {
        assertEquals(SendValidator.Result.Valid, SendValidator.validate("+15551234567", "hello"))
    }

    @Test
    fun `blank recipient is rejected`() {
        val result = SendValidator.validate("   ", "hello")

        assertEquals(SendValidator.Reason.EMPTY_RECIPIENT, (result as SendValidator.Result.Invalid).reason)
    }

    @Test
    fun `blank body is rejected`() {
        val result = SendValidator.validate("+15551234567", "   ")

        assertEquals(SendValidator.Reason.EMPTY_BODY, (result as SendValidator.Result.Invalid).reason)
    }

    @Test
    fun `recipient with only a plus is rejected`() {
        val result = SendValidator.validate("+", "hello")

        assertEquals(SendValidator.Reason.UNPARSEABLE_RECIPIENT, (result as SendValidator.Result.Invalid).reason)
    }

    @Test
    fun `too short number is rejected as a typo`() {
        val result = SendValidator.validate("12", "hello")

        assertEquals(SendValidator.Reason.UNPARSEABLE_RECIPIENT, (result as SendValidator.Result.Invalid).reason)
    }

    @Test
    fun `short code is accepted`() {
        assertEquals(SendValidator.Result.Valid, SendValidator.validate("12345", "hello"))
    }

    @Test
    fun `alphanumeric sender id is accepted as a target`() {
        assertEquals(SendValidator.Result.Valid, SendValidator.validate("Google", "hello"))
    }

    @Test
    fun `alphanumeric target with spaces is rejected`() {
        val result = SendValidator.validate("My Bank", "hello")

        assertEquals(SendValidator.Reason.UNPARSEABLE_RECIPIENT, (result as SendValidator.Result.Invalid).reason)
    }

    @Test
    fun `formatted international number is accepted`() {
        assertEquals(SendValidator.Result.Valid, SendValidator.validate("+1 (555) 123-4567", "hello"))
    }

    @Test
    fun `oversized body is rejected`() {
        val body = "a".repeat(SendValidator.MAX_BODY_LENGTH + 1)

        val result = SendValidator.validate("+15551234567", body)

        assertEquals(SendValidator.Reason.BODY_TOO_LONG, (result as SendValidator.Result.Invalid).reason)
    }

    @Test
    fun `body at the limit is accepted`() {
        val body = "a".repeat(SendValidator.MAX_BODY_LENGTH)

        assertEquals(SendValidator.Result.Valid, SendValidator.validate("+15551234567", body))
    }

    @Test
    fun `multibyte body counts characters not bytes`() {
        val body = "\u00e9".repeat(SendValidator.MAX_BODY_LENGTH)

        assertTrue(SendValidator.validate("+15551234567", body) is SendValidator.Result.Valid)
    }

    @Test
    fun `recipient is trimmed before validation`() {
        assertEquals(SendValidator.Result.Valid, SendValidator.validate("  +15551234567  ", "hi"))
    }

    @Test
    fun `valid input reports no failure`() {
        val result = SendValidator.validate("+15551234567", "hi")

        assertFalse(result is SendValidator.Result.Invalid)
    }

    @Test
    fun `comma separated recipients are all validated`() {
        assertEquals(
            SendValidator.Result.Valid,
            SendValidator.validate("+15551234567, +15559876543", "hi"),
        )
        val invalid = SendValidator.validate("+15551234567, 12", "hi")
        assertEquals(SendValidator.Reason.UNPARSEABLE_RECIPIENT, (invalid as SendValidator.Result.Invalid).reason)
    }
}