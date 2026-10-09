package com.oaktech.smsapp

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The Turkish translation is present and actually selected.
 *
 * Guards two failure modes that are invisible until a user hits them: a string
 * added to the default file and never translated, which silently falls back to
 * English, and a locale qualifier that is misspelled, which is ignored
 * completely. A test cannot catch a missing translation by asserting on the
 * English file, so the default locale is switched to Turkish and the resolved
 * value is compared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TurkishTranslationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun inTurkish(): Context {
        val locale = Locale("tr", "TR")
        Locale.setDefault(locale)
        val configuration = android.content.res.Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }

    @Test
    fun `turkish locale resolves translated strings`() {
        val tr = inTurkish()

        assertEquals("Mesajlar", tr.getString(R.string.conversations_title))
        assertEquals("Geri", tr.getString(R.string.thread_back))
        assertEquals("Gönder", tr.getString(R.string.compose_send))
        assertEquals("Kime", tr.getString(R.string.compose_recipient_hint))
    }

    @Test
    fun `the compose action is translated`() {
        assertEquals("Yeni mesaj", inTurkish().getString(R.string.conversations_compose))
    }

    @Test
    fun `notification action labels are translated`() {
        val tr = inTurkish()

        assertEquals("Yanıtla", tr.getString(R.string.notification_action_reply))
        assertEquals("Okundu", tr.getString(R.string.notification_action_read))
        assertEquals("Önemsiz", tr.getString(R.string.notification_action_spam))
        assertEquals("Sil", tr.getString(R.string.notification_action_delete))
        assertEquals("Çoklu ortam mesajı", tr.getString(R.string.notification_mms_no_text))
        assertEquals(
            "2 ek",
            tr.resources.getQuantityString(R.plurals.notification_attachments, 2, 2),
        )
    }

    @Test
    fun `message action and details strings are translated`() {
        val tr = inTurkish()

        assertEquals("Kopyala", tr.getString(R.string.message_action_copy))
        assertEquals("Paylaş", tr.getString(R.string.message_action_share))
        assertEquals("İlet", tr.getString(R.string.message_action_forward))
        assertEquals("Yeniden gönder", tr.getString(R.string.message_action_resend))
        assertEquals("Mesaj kopyalandı", tr.getString(R.string.message_copied))
        assertEquals("Mesaj ayrıntıları", tr.getString(R.string.message_details_title))
        assertEquals("Gönderildi", tr.getString(R.string.message_details_sent_at))
        assertEquals("Teslim edildi", tr.getString(R.string.message_details_delivered_at))
        assertEquals("Hata", tr.getString(R.string.message_details_error))
        assertEquals("SIM", tr.getString(R.string.message_details_sim))
    }

    @Test
    fun `search strings are translated`() {
        val tr = inTurkish()

        assertEquals("Ara", tr.getString(R.string.search_title))
        assertEquals("Mesajlarda ve kişilerde ara", tr.getString(R.string.search_hint))
        assertEquals("Eşleşen mesaj yok", tr.getString(R.string.search_empty))
    }

    @Test
    fun `notification channel name is translated`() {
        val tr = inTurkish()

        assertEquals("Gelen mesajlar", tr.getString(R.string.notification_channel_name))
        assertEquals(
            "Yeni bir metin mesajı geldiğinde uyarır",
            tr.getString(R.string.notification_channel_description),
        )
    }

    @Test
    fun `spam folder strings are translated`() {
        val tr = inTurkish()

        assertEquals("Önemsiz", tr.getString(R.string.spam_title))
        assertEquals("Önemsiz mesaj yok", tr.getString(R.string.spam_empty))
    }

    @Test
    fun `delete confirmation strings are translated`() {
        val tr = inTurkish()

        assertEquals("Mesaj silinsin mi?", tr.getString(R.string.delete_message_title))
        assertEquals("Bu mesaj kalıcı olarak silinecek.", tr.getString(R.string.delete_message_body))
        assertEquals("Sil", tr.getString(R.string.dialog_delete))
        assertEquals("Vazgeç", tr.getString(R.string.dialog_cancel))
    }

    @Test
    fun `selection mode strings are translated`() {
        val tr = inTurkish()

        assertEquals("Seçimi kapat", tr.getString(R.string.selection_close))
        assertEquals("En üste sabitle", tr.getString(R.string.conversations_pin))
        assertEquals("Sabitlemeyi kaldır", tr.getString(R.string.conversations_unpin))
        assertEquals("Önemsizlere taşı", tr.getString(R.string.conversations_move_to_spam))
        assertEquals("Gelen kutusuna taşı", tr.getString(R.string.conversations_move_to_inbox))
        assertEquals("Okunmadı olarak işaretle", tr.getString(R.string.conversations_mark_unread))
        assertEquals("Sohbetler silinsin mi?", tr.getString(R.string.delete_selected_title))
        assertEquals(
            "4 sohbet ve tüm mesajları kalıcı olarak silinecek.",
            tr.resources.getQuantityString(R.plurals.delete_selected_body, 4, 4),
        )
        assertEquals(
            "3 seçildi",
            tr.resources.getQuantityString(R.plurals.selection_count, 3, 3),
        )
    }

    @Test
    fun `phone number action strings are translated`() {
        val tr = inTurkish()

        assertEquals("Ara", tr.getString(R.string.phone_action_call))
        assertEquals("Kişilere ekle", tr.getString(R.string.phone_action_add_to_contacts))
        assertEquals("Mesaj", tr.getString(R.string.phone_action_message))
    }

    @Test
    fun `composer counter and subject strings are translated`() {
        val tr = inTurkish()

        assertEquals("Konu", tr.getString(R.string.thread_subject_hint))
        assertEquals("Mesaj konusu", tr.getString(R.string.thread_subject_label))
        // Numeric placeholders reorder nothing, but the plural is real: Turkish
        // has one form, yet the resource must still resolve without falling back.
        assertEquals(
            "12/153 · 2 parça",
            tr.resources.getQuantityString(R.plurals.thread_segment_parts, 2, 12, 153, 2),
        )
        assertEquals("12/160", tr.getString(R.string.thread_segment_counter, 12, 160))
    }

    @Test
    fun `unread count is a plural in turkish`() {
        val tr = inTurkish()
        val plurals = tr.resources.getQuantityString(
            R.plurals.conversation_unread_count,
            3,
            3,
        )

        assertEquals("3 okunmamış", plurals)
    }

    /**
     * The dial string is deliberately not translated. A translation would break
     * the send, so it is marked `translatable="false"` and must resolve to the
     * literal in every locale.
     */
    @Test
    fun `test recipient stays untranslated in turkish`() {
        assertEquals("+15551234567", inTurkish().getString(R.string.test_recipient))
    }

    /**
     * A format string whose placeholders were reordered or dropped during
     * translation would render as garbage or throw at runtime.
     */
    @Test
    fun `positional placeholders survive translation`() {
        val tr = inTurkish()

        assertEquals(
            "Siz, selam: gönderiliyor",
            tr.getString(R.string.thread_outgoing_description, "selam", "gönderiliyor"),
        )
        assertEquals(
            "0532: merhaba",
            tr.getString(R.string.thread_incoming_description, "0532", "merhaba"),
        )
        assertEquals(
            "0532 ile sohbet. merhaba. 14:30. 2 okunmamış",
            tr.getString(
                R.string.conversation_row_description,
                "0532",
                "merhaba",
                "14:30",
                tr.resources.getQuantityString(R.plurals.conversation_unread_count, 2, 2),
            ),
        )
    }

    @Test
    fun `turkish and english differ for a translatable string`() {
        val english = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(Locale.ENGLISH)
            },
        )

        assertNotEquals(
            english.getString(R.string.conversations_title),
            inTurkish().getString(R.string.conversations_title),
        )
    }
}
