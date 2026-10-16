package com.anttech.smsapp.sync

/** One file in the app's hidden Drive folder. */
data class DriveFile(
    val id: String,
    val name: String,
    /**
     * The file's revision. Drive hands this out as an opaque string that only
     * ever grows, so it is a cursor: remembering the version a device last
     * merged is how the next sync knows whether that file changed at all.
     */
    val version: Long,
    val size: Long,
)

/**
 * The slice of the Drive API the backup needs.
 *
 * A seam over `HttpURLConnection` and Google Play services, so the merge and
 * upload orchestration is driven by an in-memory fake in tests instead of a
 * network. Four calls is the whole surface; the REST API is the only supported
 * one, because Google shut the old Drive Android API down in 2019.
 */
interface DriveClient {

    /** Every file in the app data folder. */
    suspend fun listAppData(): List<DriveFile>

    /** The whole content of one file. */
    suspend fun download(fileId: String): ByteArray

    /**
     * Creates the file when [fileId] is null, otherwise replaces that file's
     * content, and returns the new revision for the caller to record.
     */
    suspend fun upload(name: String, content: ByteArray, fileId: String?): DriveFile
}

/**
 * The grant is gone: revoked in the Google account, or the account was
 * switched. Only the user can re-grant, so this must not be retried.
 */
class SignInRequiredException : Exception("Google Drive access is required")

/**
 * Play services could not produce a token, e.g. it is missing or the build has
 * no registered OAuth client. Retrying later is reasonable, so this is told
 * apart from [SignInRequiredException].
 */
class DriveUnavailableException(message: String?) : Exception(message)
