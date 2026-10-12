package com.anttech.smsapp.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class TimeFormatTest {

    /**
     * Month names are localized, so assertions need a fixed locale. The default
     * here is Turkish in some CI images, which renders January as "Oca" and
     * fails every date assertion for reasons unrelated to the logic.
     */
    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    /** Fixed clock so date boundaries are deterministic regardless of when tests run. */
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 3, 14, 15, 9, 0, 0, ZoneId.systemDefault())
    private val nowMillis: Long = now.toInstant().toEpochMilli()

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun `today shows only the time of day`() {
        val label = TimeFormat.conversationTimestamp(at(2026, 3, 14, 9, 5), nowMillis)

        assertEquals("09:05", label)
    }

    @Test
    fun `yesterday is labelled rather than dated`() {
        val label = TimeFormat.conversationTimestamp(at(2026, 3, 13, 22, 30), nowMillis)

        assertEquals("Yesterday", label)
    }

    @Test
    fun `earlier this year shows day and month without the year`() {
        val label = TimeFormat.conversationTimestamp(at(2026, 1, 2, 8, 0), nowMillis)

        assertEquals("2 Jan", label)
    }

    @Test
    fun `a previous year includes the year`() {
        val label = TimeFormat.conversationTimestamp(at(2024, 12, 25, 8, 0), nowMillis)

        assertEquals("25 Dec 2024", label)
    }

    @Test
    fun `message timestamp today omits the date`() {
        val label = TimeFormat.messageTimestamp(at(2026, 3, 14, 7, 45), nowMillis)

        assertEquals("07:45", label)
    }

    @Test
    fun `message timestamp on another day includes the date`() {
        val label = TimeFormat.messageTimestamp(at(2026, 3, 12, 7, 45), nowMillis)

        assertEquals("12 Mar, 07:45", label)
    }

    @Test
    fun `date separator distinguishes today yesterday and older`() {
        assertEquals("Today", TimeFormat.dateSeparator(at(2026, 3, 14, 1, 0), nowMillis))
        assertEquals("Yesterday", TimeFormat.dateSeparator(at(2026, 3, 13, 23, 59), nowMillis))
        assertEquals("10 Mar", TimeFormat.dateSeparator(at(2026, 3, 10, 23, 59), nowMillis))
        assertEquals("1 Jan 2025", TimeFormat.dateSeparator(at(2025, 1, 1, 0, 0), nowMillis))
    }

    @Test
    fun `timestamps on either side of midnight are not the same day`() {
        val lateToday = at(2026, 3, 14, 23, 59)
        val earlyTomorrow = at(2026, 3, 15, 0, 1)

        assertFalse(TimeFormat.isSameDay(lateToday, earlyTomorrow))
    }

    @Test
    fun `timestamps within one day are the same day`() {
        val morning = at(2026, 3, 14, 0, 0)
        val night = at(2026, 3, 14, 23, 59)

        assertTrue(TimeFormat.isSameDay(morning, night))
    }

    @Test
    fun `identical timestamps are the same day`() {
        val moment = at(2026, 3, 14, 12, 0)

        assertTrue(TimeFormat.isSameDay(moment, moment))
    }
}