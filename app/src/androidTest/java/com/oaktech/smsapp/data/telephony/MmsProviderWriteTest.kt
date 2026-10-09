package com.oaktech.smsapp.data.telephony

import android.content.Context
import android.app.role.RoleManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies [PlatformTelephonyMessageWriter]'s MMS write against the real
 * Telephony provider.
 *
 * The MMS write path is the one piece that cannot be exercised off-device: the
 * restrictions on the part table (no `_data` from a foreign package) only exist
 * in the platform provider, so a fake writer would prove nothing. This test
 * inserts a row, reads it back through the provider's own URIs and deletes it.
 *
 * It needs the default-SMS role, which the provider enforces on every write; the
 * role is granted from the shell first, and the test skips when it cannot be.
 * Run with a booted emulator: `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class MmsProviderWriteTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver
    private val writer = PlatformTelephonyMessageWriter(context)

    private var insertedId: Long? = null

    @Before
    fun becomeDefaultSmsApp() {
        // The provider only accepts MMS writes from the default SMS app, so grant
        // the role through the shell (which holds MANAGE_ROLE_HOLDERS) and skip
        // cleanly if this build cannot hold it.
        runCatching { shell("cmd role add-role-holder android.app.role.SMS ${context.packageName}") }
        val roleManager = context.getSystemService(RoleManager::class.java)
        assumeTrue(
            "not the default SMS app: ${context.packageName}",
            roleManager.isRoleHeld(RoleManager.ROLE_SMS),
        )
    }

    @After
    fun deleteRow() {
        runBlocking {
            insertedId?.let { id ->
                runCatching { writer.delete(listOf(ProviderRowRef(id, isMms = true))) }
            }
            Unit
        }
    }

    @Test
    fun writesRowAddressAndMediaPartThroughTheProvider() = runBlocking {
        val media = File(context.cacheDir, "provider-write-part.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        }
        val message = ProviderMms(
            address = "+15551230000",
            recipients = emptyList(),
            subject = "provider write test",
            timestampMillis = System.currentTimeMillis(),
            parts = listOf(
                ProviderMmsPart("text/plain", "0", null, null, "hello from the test"),
                ProviderMmsPart("image/jpeg", "1", media.name, media.absolutePath, null),
            ),
            outgoing = false,
        )

        val id = writer.insertInboundMms(message)
        insertedId = id
        assertNotNull("the provider refused the MMS row", id)
        requireNotNull(id)

        val rowUri = android.content.ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
        resolver.query(rowUri, null, null, null, null)!!.use { cursor ->
            assertTrue("no row at $rowUri", cursor.moveToFirst())
            assertEquals(
                "provider write test",
                cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT)),
            )
            assertTrue(
                "thread id should resolve, not stay 0",
                cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)) > 0,
            )
        }

        val addrUri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, "$id/addr")
        var fromSeen = false
        resolver.query(addrUri, null, null, null, null)!!.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS)) == message.address &&
                    cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.Addr.TYPE)) == 0x89
                ) {
                    fromSeen = true
                }
            }
        }
        assertTrue("From address row missing", fromSeen)

        val partUri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, "$id/part")
        var textSeen = false
        var imageSeen = false
        resolver.query(partUri, null, null, null, null)!!.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow("_id")
            val typeColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)
            while (cursor.moveToNext()) {
                when (cursor.getString(typeColumn)) {
                    "text/plain" -> {
                        textSeen = true
                        assertEquals(
                            "hello from the test",
                            cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT)),
                        )
                    }

                    "image/jpeg" -> {
                        imageSeen = true
                        // The bytes have to be streamed back through the part URI:
                        // the provider mints the file and never exposes its path.
                        val mediaUri = Uri.withAppendedPath(
                            Telephony.Mms.Part.CONTENT_URI,
                            cursor.getLong(idColumn).toString(),
                        )
                        val bytes = resolver.openInputStream(mediaUri)!!.use { it.readBytes() }
                        assertArrayEquals(media.readBytes(), bytes)
                    }
                }
            }
        }
        assertTrue("text part missing", textSeen)
        assertTrue("media part bytes missing", imageSeen)
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        // Read to EOF so the command is not killed while still running.
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
