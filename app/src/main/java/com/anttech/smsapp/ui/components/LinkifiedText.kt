package com.anttech.smsapp.ui.components

import android.content.Intent
import android.provider.ContactsContract
import android.util.Patterns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anttech.smsapp.R
import com.anttech.smsapp.data.DataGraph
import com.anttech.smsapp.sms.block.BlockedSenders
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * A [Text] that turns URLs, email addresses, WhatsApp links, phone numbers and
 * coordinates in [text] into clickable ranges.
 *
 * Anything but a phone number resolves through the default [LinkAnnotation]
 * handler: http(s) links, `mailto:`, `tel:` and `geo:` URIs open their
 * respective device apps. A tapped phone number instead shows a bottom sheet
 * with Message, Call, and with an "Add to contacts" action when the number is
 * not already a contact.
 */
@Composable
fun LinkifiedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onMessage: (String) -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    var tappedNumber by remember { mutableStateOf<String?>(null) }

    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyles = remember(linkColor) {
        TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    }
    Text(
        text = remember(text, linkStyles) {
            linkify(text, linkStyles) { href ->
                if (href.startsWith("tel:")) {
                    tappedNumber = href.removePrefix("tel:")
                } else {
                    uriHandler.openUri(href)
                }
            }
        },
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
    )

    tappedNumber?.let { number ->
        PhoneActionsSheet(number = number, onDismiss = { tappedNumber = null }, onMessage = onMessage)
    }
}

/**
 * An annotated copy of [text] where every detected link becomes a
 * [LinkAnnotation.Url]. When [onClick] is null the framework's default
 * handler opens each link.
 */
internal fun linkify(
    text: String,
    linkStyles: TextLinkStyles = TextLinkStyles(),
    onClick: ((String) -> Unit)? = null,
): AnnotatedString {
    val spans = collect(text)
    val chosen = chooseNonOverlapping(spans)
    val listener = onClick?.let { handler ->
        LinkInteractionListener { annotation ->
            handler((annotation as LinkAnnotation.Url).url)
        }
    }
    return buildAnnotatedString {
        append(text)
        chosen.forEach { (start, end, href) ->
            addLink(LinkAnnotation.Url(href, linkStyles, listener), start, end)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneActionsSheet(number: String, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val resolver = remember(context) { DataGraph.get(context).contactNames }
    var contactName by remember(number) { mutableStateOf<String?>(null) }
    LaunchedEffect(number) {
        contactName = if (resolver.hasPermission()) resolver.displayName(number) else null
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = contactName ?: number,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            // Messaging is the natural first action inside an SMS app, so it
            // sits above the call. The composer opens pre-filled with this
            // number, exactly like a fresh compose to a picked contact.
            ListItem(
                headlineContent = { Text(stringResource(R.string.phone_action_message)) },
                modifier = Modifier.fillMaxWidth().clickable {
                    onDismiss()
                    onMessage(number)
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.phone_action_call)) },
                modifier = Modifier.fillMaxWidth().clickable {
                    onDismiss()
                    uriHandler.openUri("tel:$number")
                },
            )
            if (contactName == null) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.phone_action_add_to_contacts))
                    },
                    modifier = Modifier.fillMaxWidth().clickable {
                        onDismiss()
                        runCatching { context.startActivity(addToContactsIntent(number)) }
                    },
                )
            }
            // Blocking is offering the sender identity the receive path compares
            // against, so the conversation this number belongs to goes quiet from
            // the next message onward.
            ListItem(
                headlineContent = { Text(stringResource(R.string.phone_action_block)) },
                modifier = Modifier.fillMaxWidth().clickable {
                    onDismiss()
                    BlockedSenders(context).add(number)
                },
            )
        }
    }
}

private fun addToContactsIntent(number: String): Intent =
    Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.PHONE, number)
    }

private data class RawSpan(val start: Int, val end: Int, val href: String)

private val WHATSAPP_URL = Pattern.compile(
    "(?:wa\\.me|chat\\.whatsapp\\.com|whatsapp\\.com)/\\S+",
    Pattern.CASE_INSENSITIVE,
)

private val COORDINATES = Pattern.compile(
    "(-?\\d{1,3}(?:\\.\\d{1,7})?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d{1,7})?)",
)

/** Every kind of link that can appear in a message body, matching in one pass. */
private fun collect(text: String): List<RawSpan> {
    val regions = mutableListOf<RawSpan>()

    fun scan(pattern: Pattern, href: (String) -> String?) {
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            href(matcher.group())?.let { regions += RawSpan(matcher.start(), matcher.end(), it) }
        }
    }

    scan(Patterns.EMAIL_ADDRESS) { "mailto:$it" }
    scan(WHATSAPP_URL) { resolveUrlHref(it) }
    scan(COORDINATES) { coordinateHref(it) }
    scan(Patterns.WEB_URL) { resolveUrlHref(it) }
    scan(Patterns.PHONE) { match ->
        val digits = match.count { it.isDigit() }
        if (digits < 7 || digits > 15) null else phoneHref(match)
    }
    return regions
}

/**
 * Keeps the longest spans and drops any that overlap them, so a phone number
 * or email embedded in a URL is not split into two links.
 */
private fun chooseNonOverlapping(spans: List<RawSpan>): List<RawSpan> {
    val sorted = spans.sortedWith(
        compareByDescending<RawSpan> { it.end - it.start }.thenBy { it.start },
    )
    val chosen = mutableListOf<RawSpan>()
    for (span in sorted) {
        if (chosen.none { it.start < span.end && span.start < it.end }) {
            chosen += span
        }
    }
    return chosen
}

private fun coordinateHref(match: String): String? {
    val matcher = COORDINATES.matcher(match)
    if (!matcher.matches()) return null
    val lat = matcher.group(1)!!.toDoubleOrNull() ?: return null
    val lng = matcher.group(2)!!.toDoubleOrNull() ?: return null
    if (abs(lat) > 90 || abs(lng) > 180) return null
    return "geo:$lat,$lng?q=$lat,$lng"
}

private fun phoneHref(match: String): String =
    "tel:${match.filter { it.isDigit() || it == '+' }}"

private fun resolveUrlHref(match: String): String =
    if (match.startsWith("http://") || match.startsWith("https://")) match else "http://$match"