package com.anttech.smsapp.ui.components

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinkifiedTextTest {

    private val styles = TextLinkStyles()

    private fun hrefs(text: String): Map<IntRange, String> {
        val annotated = linkify(text, styles)
        return annotated.getLinkAnnotations(0, annotated.length)
            .filter { it.item is LinkAnnotation.Url }
            .associate { (it.start until it.end) to (it.item as LinkAnnotation.Url).url }
    }

    @Test
    fun `a plain url links to itself`() {
        val links = hrefs("Read https://example.com/a now")

        assertEquals(mapOf(5..25 to "https://example.com/a"), links)
    }

    @Test
    fun `a bare www url gains a scheme`() {
        val links = hrefs("See www.example.com/docs")

        assertEquals(mapOf(4..23 to "http://www.example.com/docs"), links)
    }

    @Test
    fun `an email opens the mail client`() {
        val links = hrefs("mail help@example.com please")

        assertEquals(mapOf(5..20 to "mailto:help@example.com"), links)
    }

    @Test
    fun `a whatsapp link keeps its deep path`() {
        val links = hrefs("text wa.me/15551234567")

        assertTrue(links.values.any { it == "http://wa.me/15551234567" })
    }

    @Test
    fun `a phone number opens the dialer`() {
        val links = hrefs("Call +1 (555) 123-4567 soon")

        assertEquals(mapOf(5..21 to "tel:+15551234567"), links)
    }

    @Test
    fun `coordinates open the navigator`() {
        val links = hrefs("Meet at 37.7749, -122.4194 today")

        assertEquals(
            mapOf(8..25 to "geo:37.7749,-122.4194?q=37.7749,-122.4194"),
            links,
        )
    }

    @Test
    fun `a number range that cannot be coordinates is left alone`() {
        val links = hrefs("order 123, 456 boxes")

        assertTrue(links.isEmpty())
    }

    @Test
    fun `a phone inside a url does not split the url`() {
        val links = hrefs("see https://example.com/status/12345")

        assertEquals(mapOf(4..35 to "https://example.com/status/12345"), links)
    }

    @Test
    fun `emails are not split into host urls`() {
        val links = hrefs("only foo@gmail.com here")

        assertEquals(mapOf(5..17 to "mailto:foo@gmail.com"), links)
    }

    @Test
    fun `plain text has no links`() {
        assertTrue(hrefs("just words").isEmpty())
    }

    @Test
    fun `a registered click handler receives the tapped href`() {
        val seen = mutableListOf<String>()
        val annotated = linkify("call 555-123-4567 soon") { seen += it }
        val url = annotated.getLinkAnnotations(0, annotated.length)
            .mapNotNull { it.item as? LinkAnnotation.Url }
            .single()

        url.linkInteractionListener?.onClick(url)

        assertEquals(listOf("tel:5551234567"), seen)
    }

    @Test
    fun `without a click handler the default listener is used`() {
        val annotated = linkify("go https://example.com")
        val url = annotated.getLinkAnnotations(0, annotated.length)
            .mapNotNull { it.item as? LinkAnnotation.Url }
            .single()

        assertEquals("https://example.com", url.url)
    }
}