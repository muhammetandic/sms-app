package com.oaktech.smsapp.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Timestamp formatting for the conversation list and thread view.
 *
 * Timezone-aware and locale-aware via [java.time], so this stays correct
 * regardless of the device locale. Deliberately free of Android types so the
 * formatting rules can be tested on the JVM.
 */
object TimeFormat {

    private val timeOfDay: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dayAndMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
    private val dayAndYear: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

    /** Short label for a conversation row: time today, otherwise a date. */
    fun conversationTimestamp(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val moment = Instant.ofEpochMilli(timestampMillis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

        return when (moment.toLocalDate()) {
            today -> timeOfDay.format(moment)
            today.minusDays(1) -> "Yesterday"
            else -> if (moment.year == today.year) {
                dayAndMonth.format(moment)
            } else {
                dayAndYear.format(moment)
            }
        }
    }

    /** Full timestamp shown inside a thread. */
    fun messageTimestamp(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val moment = Instant.ofEpochMilli(timestampMillis).atZone(zone)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)

        return if (moment.toLocalDate() == now.toLocalDate()) {
            timeOfDay.format(moment)
        } else {
            "${dayAndMonth.format(moment)}, ${timeOfDay.format(moment)}"
        }
    }

    /**
     * Groups a thread for the conversation list, newest first.
     *
     * Conversation rows show a date separator rather than a full date so the
     * grouping matches what the user sees.
     */
    fun dateSeparator(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): String? {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

        return when {
            date == today -> "Today"
            date == today.minusDays(1) -> "Yesterday"
            date.year == today.year -> dayAndMonth.format(date)
            else -> dayAndYear.format(date)
        }
    }

    /** True when the message is recent enough to group under a single day header. */
    fun isSameDay(firstMillis: Long, secondMillis: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val first = Instant.ofEpochMilli(firstMillis).atZone(zone).toLocalDate()
        val second = Instant.ofEpochMilli(secondMillis).atZone(zone).toLocalDate()
        return first == second
    }
}