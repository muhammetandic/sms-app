package com.anttech.smsapp.sync

/**
 * The answer to "give me a Drive token, without showing anything".
 *
 * There is no failure-to-refresh state on purpose: an access token lasts about
 * an hour, and Google Play services mints a new one silently from the stored
 * grant every time it is asked. So the only interesting outcomes are "a token",
 * "there is no grant on this device" and "Play services could not answer".
 */
sealed interface TokenResult {

    /** A usable bearer token. */
    data class Token(val value: String) : TokenResult

    /**
     * No grant on this device, or it was revoked. Only the settings screen can
     * obtain one, so a background sync stops here rather than retrying.
     */
    data object NeedsSignIn : TokenResult

    /**
     * Google Play services itself could not answer, e.g. it is missing, out of
     * date, or the app was built without a registered OAuth client. Unlike
     * [NeedsSignIn] this may resolve on its own, so the caller retries later.
     */
    data class Unavailable(val message: String?) : TokenResult
}

/**
 * Hands out an access token for the Drive app-data scope.
 *
 * A seam over Google Play services, like `SmsTransport` is over `SmsManager`:
 * the sync orchestration and its tests deal in this interface, and only
 * [GoogleAccessTokenProvider] knows about `Identity`.
 */
interface AccessTokenProvider {

    /** A silent token request. */
    suspend fun accessToken(): TokenResult
}
