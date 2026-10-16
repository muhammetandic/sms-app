package com.anttech.smsapp.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two pieces of [HttpDriveClient] that are pure JSON and can be pinned down
 * without a server. The rest of the class is the HTTP seam and stays hand-tested.
 *
 * Robolectric because both functions lean on Android's `org.json`, which is a
 * stub off-device - and because that specific implementation is what the upload
 * metadata bug lived in. A plain JVM test would exercise a different `org.json`
 * that serialises a `List` correctly and would have passed while the device
 * failed.
 */
@RunWith(RobolectricTestRunner::class)
class HttpDriveClientTest {

    /**
     * The bug this pins down: Android's `org.json` does not treat a Kotlin
     * `List` as a JSON array. `put("parents", listOf(...))` serialises to the
     * *string* `"[appDataFolder]"`, Drive finds no usable parent, files land in
     * My Drive, and the `drive.appdata` scope is refused with 403
     * `insufficientFilePermissions`. Asserting on the parsed type, not on the
     * text, is what makes the test catch a regression to a list.
     */
    @Test
    fun `upload metadata carries parents as a JSON array`() {
        val metadata = JSONObject(appDataMetadata("messages-abc.jsonl.gz"))

        assertEquals("messages-abc.jsonl.gz", metadata.getString("name"))

        val parents = metadata.get("parents")
        assertTrue(
            "parents must be a JSON array, not a ${parents.javaClass.simpleName}",
            parents is JSONArray,
        )
        assertEquals("appDataFolder", (parents as JSONArray).getString(0))
        assertEquals(1, parents.length())
    }

    @Test
    fun `upload metadata is stable for an arbitrary name`() {
        val name = "messages-0f8e-4c1a.jsonl.gz"
        assertEquals(
            """{"name":"$name","parents":["appDataFolder"]}""",
            JSONObject(appDataMetadata(name)).toString(),
        )
    }

    /**
     * Drive spells the two opposite failures almost identically, so each side of
     * the split is asserted. Getting this wrong is how a malformed upload
     * request reached the user as "sign in again".
     */
    @Test
    fun `scope refusals are recognised`() {
        assertTrue("""{"reason":"insufficientPermissions"}""".isScopeRefusal())
        assertTrue(
            """{"message":"Request had insufficient authentication scopes."}"""
                .isScopeRefusal(),
        )
    }

    @Test
    fun `file permission failures are not mistaken for scope refusals`() {
        assertFalse(
            """{"reason":"insufficientFilePermissions","message":"The user does not have sufficient permissions for this file."}"""
                .isScopeRefusal(),
        )
        assertFalse("""{"error":{"code":404}}""".isScopeRefusal())
    }
}
