package com.oaktech.smsapp.sms.mms

import android.content.Context
import com.oaktech.smsapp.data.DataGraph
import java.io.File

/**
 * Deletes attachment files that no store row points at any more.
 *
 * Deleting a message or a conversation removes its rows but not the bytes under
 * `filesDir/mms/parts`, so without a sweep the directory grows forever. A file
 * is kept while a message attachment or a composer's unsent queue references it.
 *
 * A file newer than [graceMillis] is also kept: the bytes are written before the
 * row that references them is committed, so a file whose row has not landed yet
 * would otherwise look orphaned and be deleted mid-write.
 */
class MmsAttachmentCleaner(
    private val dir: File,
    private val referencedPaths: suspend () -> Set<String>,
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** @return how many files were removed. */
    suspend fun prune(): Int {
        if (!dir.isDirectory) return 0

        // A store that cannot answer is not evidence the files are orphans; the
        // sweep is opportunistic, so it does nothing rather than guess.
        val referenced = runCatching { referencedPaths() }.getOrNull() ?: return 0
        val cutoff = now() - graceMillis

        var deleted = 0
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.absolutePath in referenced) return@forEach
            if (file.lastModified() > cutoff) return@forEach
            if (file.delete()) deleted++
        }
        return deleted
    }

    companion object {
        const val DEFAULT_GRACE_MILLIS = 5 * 60 * 1000L

        /** The attachment directory this sweep owns, from the app's file store. */
        fun directory(context: Context): File =
            File(context.filesDir, MmsAttachmentStore.MMSPART_DIR)

        /**
         * Runs one sweep in the background against the process store. Called at
         * startup; point-in-time, so a failure (no store yet, IO error) is
         * swallowed rather than crashing the app.
         */
        suspend fun pruneStore(context: Context): Int {
            val repository = DataGraph.get(context).messageRepository
            return MmsAttachmentCleaner(
                dir = directory(context),
                referencedPaths = repository::referencedAttachmentPaths,
            ).prune()
        }
    }
}
