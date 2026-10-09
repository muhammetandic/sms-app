package com.oaktech.smsapp.sms.mms

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The sweep is the only thing deleting attachment bytes, so it has to be both
 * decisive (orphans go) and careful (anything still referenced, or too young to
 * have a committed row, stays).
 */
class MmsAttachmentCleanerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val now = 1_000_000L

    private fun agedFile(name: String, ageMillis: Long): File =
        folder.newFile(name).apply {
            writeBytes(byteArrayOf(1))
            setLastModified(now - ageMillis)
        }

    @Test
    fun `an unreferenced old file is deleted`() = runBlocking {
        val orphan = agedFile("orphan.jpg", ageMillis = 600_000)
        val cleaner = MmsAttachmentCleaner(folder.root, referencedPaths = { emptySet() }, now = { now })

        val deleted = cleaner.prune()

        assertEquals(1, deleted)
        assertFalse(orphan.exists())
    }

    @Test
    fun `a referenced file is kept`() = runBlocking {
        val kept = agedFile("kept.jpg", ageMillis = 600_000)
        val cleaner = MmsAttachmentCleaner(
            folder.root,
            referencedPaths = { setOf(kept.absolutePath) },
            now = { now },
        )

        val deleted = cleaner.prune()

        assertEquals(0, deleted)
        assertTrue(kept.exists())
    }

    @Test
    fun `a file inside the grace window is kept`() = runBlocking {
        // One second old vs the five-minute default: a writer may still be about
        // to commit the row that references it.
        val fresh = agedFile("fresh.jpg", ageMillis = 1_000)
        val cleaner = MmsAttachmentCleaner(folder.root, referencedPaths = { emptySet() }, now = { now })

        val deleted = cleaner.prune()

        assertEquals(0, deleted)
        assertTrue(fresh.exists())
    }

    /**
     * A store that cannot answer must not be read as "nothing is referenced":
     * that would delete every attachment in one bad sweep.
     */
    @Test
    fun `a failing store deletes nothing`() = runBlocking {
        val orphan = agedFile("orphan.jpg", ageMillis = 600_000)
        val cleaner = MmsAttachmentCleaner(
            folder.root,
            referencedPaths = { throw IllegalStateException("database closed") },
            now = { now },
        )

        val deleted = cleaner.prune()

        assertEquals(0, deleted)
        assertTrue(orphan.exists())
    }

    @Test
    fun `a missing directory is a no-op`() = runBlocking {
        val cleaner = MmsAttachmentCleaner(
            File(folder.root, "does-not-exist"),
            referencedPaths = { emptySet() },
            now = { now },
        )

        assertEquals(0, cleaner.prune())
    }
}
