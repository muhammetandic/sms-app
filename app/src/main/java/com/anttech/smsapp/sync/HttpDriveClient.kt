package com.anttech.smsapp.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [DriveClient] over the Drive REST API v3.
 *
 * `HttpURLConnection` rather than an HTTP dependency: the whole API surface
 * needed is four requests, and the alternative is a client library carried for
 * them. Everything is HTTPS, so no cleartext policy is involved.
 *
 * The bearer token is fetched per request rather than held. Play services
 * caches the token it hands out, so this is a cheap local call, and it is what
 * lets a sync that runs past the token's hour simply mint another.
 */
class HttpDriveClient(private val tokens: AccessTokenProvider) : DriveClient {

    override suspend fun listAppData(): List<DriveFile> {
        val response = request("GET", "$FILES_URL?spaces=appDataFolder&fields=files(id,name,version,size)&pageSize=100")
        val files = JSONObject(String(response, Charsets.UTF_8)).optJSONArray("files")
            ?: return emptyList()
        return buildList {
            for (index in 0 until files.length()) {
                val file = files.optJSONObject(index) ?: continue
                val id = file.optString("id").takeIf { it.isNotEmpty() } ?: continue
                add(
                    DriveFile(
                        id = id,
                        name = file.optString("name"),
                        version = file.optString("version").toLongOrNull() ?: 0L,
                        size = file.optString("size").toLongOrNull() ?: 0L,
                    ),
                )
            }
        }
    }

    override suspend fun download(fileId: String): ByteArray =
        request("GET", "$FILES_URL/$fileId?alt=media")

    override suspend fun upload(name: String, content: ByteArray, fileId: String?): DriveFile =
        if (fileId == null) create(name, content) else replace(fileId, content)

    /**
     * A create is the one request that needs two parts in one body: the file's
     * metadata (its name and the `appDataFolder` parent, which is what puts it
     * in the hidden folder) plus the bytes. Drive will not infer either.
     */
    private suspend fun create(name: String, content: ByteArray): DriveFile {
        val boundary = "sms_sync_${UUID.randomUUID().toString().replace("-", "")}"
        val body = multipartBody(boundary, name, content)
        val response = request(
            method = "POST",
            url = "$UPLOAD_URL?uploadType=multipart&fields=id,name,version,size",
            body = body,
            contentType = "multipart/related; boundary=$boundary",
        )
        return parseFile(response, fallbackName = name, fallbackSize = content.size.toLong())
    }

    /**
     * A replace is a bare media upload: the file already has its name and its
     * parent, and `uploadType=media` leaves both alone.
     */
    private suspend fun replace(fileId: String, content: ByteArray): DriveFile {
        val response = request(
            method = "PATCH",
            url = "$UPLOAD_URL/$fileId?uploadType=media&fields=id,name,version,size",
            body = content,
            contentType = "application/gzip",
        )
        return parseFile(response, fallbackName = "", fallbackSize = content.size.toLong())
    }

    private suspend fun request(
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): ByteArray {
        val token = when (val result = tokens.accessToken()) {
            is TokenResult.Token -> result.value
            TokenResult.NeedsSignIn -> throw SignInRequiredException()
            is TokenResult.Unavailable -> throw DriveUnavailableException(result.message)
        }

        return withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                if (body != null) {
                    doOutput = true
                    contentType?.let { setRequestProperty("Content-Type", it) }
                }
            }
            try {
                if (body != null) {
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                // The error stream carries Drive's explanation; reading it off
                // the response body would throw instead of returning it.
                val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes() }
                    ?: ByteArray(0)

                when {
                    // 401 is an expired or invalid token; a 403 whose reason is
                    // `insufficientPermissions` (or whose message blames the
                    // authentication scopes) is a scope the account no longer
                    // has. Either way only a new grant helps, so neither is
                    // retried.
                    //
                    // Deliberately not a bare `contains("insufficient")`: Drive
                    // also answers 403 with `insufficientFilePermissions` when a
                    // file is outside the app's reach - an empty or malformed
                    // `parents` will do it - and that is a bug in the request, to
                    // be retried and seen, not a prompt to sign in again. The
                    // substring `insufficientPermissions` does not match
                    // `insufficientFilePermissions`, so the two stay apart.
                    code == HttpURLConnection.HTTP_UNAUTHORIZED -> throw SignInRequiredException()
                    code == HttpURLConnection.HTTP_FORBIDDEN &&
                        String(response, Charsets.UTF_8).isScopeRefusal() ->
                        throw SignInRequiredException()

                    code !in 200..299 -> throw IOException(
                        "Drive $method failed: HTTP $code ${String(response, Charsets.UTF_8)}",
                    )

                    else -> response
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun multipartBody(boundary: String, name: String, content: ByteArray): ByteArray {
        val metadata = appDataMetadata(name)
        return ByteArrayOutputStream().apply {
            write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray(Charsets.UTF_8))
            write(metadata.toByteArray(Charsets.UTF_8))
            write("\r\n".toByteArray(Charsets.UTF_8))
            write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            write("Content-Type: application/gzip\r\n\r\n".toByteArray(Charsets.UTF_8))
            write(content)
            write("\r\n".toByteArray(Charsets.UTF_8))
            write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        }.toByteArray()
    }

    private fun parseFile(response: ByteArray, fallbackName: String, fallbackSize: Long): DriveFile {
        val json = JSONObject(String(response, Charsets.UTF_8))
        return DriveFile(
            id = json.optString("id"),
            name = json.optString("name", fallbackName),
            version = json.optString("version").toLongOrNull() ?: 0L,
            size = json.optString("size").toLongOrNull() ?: fallbackSize,
        )
    }

    private companion object {
        const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val READ_TIMEOUT_MILLIS = 60_000
    }
}

/** The hidden per-app folder; Drive creates it on the first file. */
private const val APPDATA_FOLDER = "appDataFolder"

/**
 * The metadata half of a multipart upload.
 *
 * `parents` is a [JSONArray] and not a Kotlin list, which is the entire reason
 * this is a named function. Android's `org.json` does not recognise a `List` as
 * an array: it falls back to quoting the value's `toString()`, emitting
 * `"parents":"[appDataFolder]"`. Drive then finds no usable parent, creates the
 * file in My Drive instead of the app data folder, and refuses with 403
 * `insufficientFilePermissions` - the `drive.appdata` scope reaches app-data
 * files only. An explicit [JSONArray] serialises to a real JSON array on every
 * implementation, which is what the reference `org.json` and the Drive API both
 * expect.
 */
internal fun appDataMetadata(name: String): String =
    JSONObject()
        .put("name", name)
        .put("parents", JSONArray().put(APPDATA_FOLDER))
        .toString()

/**
 * Whether a 403 body is the account's scope being refused, rather than the
 * app's own file permissions.
 *
 * The two spellings are nearly identical and mean opposite things: Drive's
 * `insufficientPermissions` (or its "insufficient authentication scopes"
 * message) says only a fresh grant helps, while `insufficientFilePermissions`
 * says the request itself was wrong and retrying it is exactly the right
 * response. Testing for a bare "insufficient" conflates them, which is how a
 * malformed upload request once surfaced to the user as "sign in again". The
 * substrings do not overlap, so the two stay apart.
 */
internal fun String.isScopeRefusal(): Boolean =
    contains("insufficientPermissions") ||
        contains("insufficient authentication scopes", ignoreCase = true)
