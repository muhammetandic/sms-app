package com.anttech.smsapp.sync

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The one Google grant this app asks for.
 *
 * `drive.appdata` is the narrowest Drive scope there is: it reaches only this
 * app's own hidden folder and cannot read, list or change anything else in the
 * account. It is also classified non-sensitive, so it needs no Google
 * verification review - unlike `drive.file`, and unlike the full `drive` scope
 * that the old Drive Android API used.
 *
 * Two entry points, and the difference matters. [silentToken] is what a
 * background worker calls: it asks for a token and never launches anything
 * itself, so a missing grant comes back as [TokenResult.NeedsSignIn] rather
 * than as something to start. It is not a promise that Google stays quiet -
 * the API has no prompt-free mode, so in the foreground Play services can
 * still put up its own consent screen, and that is treated as "not signed in"
 * like any other refusal. [begin] is what the settings screen calls, where a
 * [SetUpResult.Resolution] is expected and launched by the caller, and the
 * returned intent comes back through [resultFromIntent]. Nothing here runs on
 * a background thread the user cannot see.
 */
object GoogleDriveAuth {

    /**
     * Scope string, kept as the literal Google documents. It is passed to
     * `Scope(...)` rather than compiled from a constant, because the scope is a
     * wire value, not a code symbol.
     */
    const val APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    /** What happened when the sign-in flow was started. */
    sealed interface SetUpResult {

        /** The grant exists or was just granted; silent tokens work from now on. */
        data object Authorized : SetUpResult

        /** Google needs the user to answer something before issuing a token. */
        data class Resolution(val intentSender: IntentSender) : SetUpResult

        /** The flow could not run, e.g. Play services is absent or too old. */
        data class Failed(val message: String?) : SetUpResult
    }

    /**
     * Asks for a token, launching nothing. A [TokenResult.NeedsSignIn] here is
     * the normal answer on a fresh install, and the settings screen turns it
     * into the interactive flow.
     *
     * The request cannot be made strictly prompt-free: the API's only prompt
     * values are CONSENT and SELECT_ACCOUNT, and leaving the prompt unset lets
     * Play services choose. So a foreground caller - the "Sync now" button -
     * can still see Google's own consent screen appear from this call. That is
     * Play services' choice, not a flow this code started, and the answer is
     * reported as not-signed-in either way; the background worker, whose
     * activity start the platform blocks, just gets the same result.
     */
    suspend fun silentToken(context: Context): TokenResult =
        try {
            Identity.getAuthorizationClient(context.applicationContext)
                .authorize(request())
                .await()
                .tokenOrNeedsSignIn()
        } catch (api: ApiException) {
            if (api.isResolutionRequired()) TokenResult.NeedsSignIn
            else TokenResult.Unavailable(api.message)
        } catch (error: Exception) {
            TokenResult.Unavailable(error.message)
        }

    /**
     * Starts the grant, returning the pending intent to launch when Google
     * needs the user. The intent sender is launched by the caller (which owns
     * an Activity result registry) and its result is resolved with
     * [resultFromIntent].
     */
    suspend fun begin(context: Context): SetUpResult =
        try {
            val result = Identity.getAuthorizationClient(context.applicationContext)
                .authorize(request())
                .await()
            if (result.hasResolution()) {
                result.pendingIntent
                    ?.let { SetUpResult.Resolution(it.intentSender) }
                    ?: SetUpResult.Failed(null)
            } else {
                SetUpResult.Authorized
            }
        } catch (api: ApiException) {
            SetUpResult.Failed(api.message)
        } catch (error: Exception) {
            SetUpResult.Failed(error.message)
        }

    /** Reads the outcome of the resolution launched from [begin]. */
    fun resultFromIntent(context: Context, data: Intent): SetUpResult =
        try {
            val result = Identity.getAuthorizationClient(context.applicationContext)
                .getAuthorizationResultFromIntent(data)
            if (result.tokenOrNeedsSignIn() is TokenResult.Token) {
                SetUpResult.Authorized
            } else {
                SetUpResult.Failed(null)
            }
        } catch (api: ApiException) {
            SetUpResult.Failed(api.message)
        } catch (error: Exception) {
            SetUpResult.Failed(error.message)
        }

    /**
     * The request carries the scope and nothing else.
     *
     * There is deliberately no client id here, and no way to add one: the only
     * builder method that takes a server client id is `requestOfflineAccess`,
     * which also asks Google for an authorization code for a backend to redeem.
     * This app has no backend, so that is not a smaller version of this call,
     * it is a different feature. Omitting a client id is not a gap - Play
     * services authorizes against the Android client registered for this
     * package name and signing certificate, and finds it unaided.
     */
    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(APPDATA_SCOPE)))
            .build()

    /**
     * A resolved result carries the token; one that still needs the user carries
     * a pending intent and no token. Reading `accessToken` in that second case
     * yields null rather than throwing, so a null token is the same signal as
     * `hasResolution()` and the caller can branch on this alone.
     */
    private fun AuthorizationResult.tokenOrNeedsSignIn(): TokenResult =
        accessToken?.let { TokenResult.Token(it) } ?: TokenResult.NeedsSignIn

    private fun ApiException.isResolutionRequired(): Boolean =
        statusCode == CommonStatusCodes.RESOLUTION_REQUIRED ||
            statusCode == CommonStatusCodes.SIGN_IN_REQUIRED
}

/** [AccessTokenProvider] over Google Play services. */
class GoogleAccessTokenProvider(private val context: Context) : AccessTokenProvider {

    override suspend fun accessToken(): TokenResult = GoogleDriveAuth.silentToken(context)
}

/**
 * Bridges a Play services [Task] to a coroutine.
 *
 * `kotlinx-coroutines-play-services`' `Task.await` would do this, but it is not a
 * declared dependency here: it arrives only transitively, inside
 * `play-services-auth`, so reaching for it would mean the auth artifact's
 * dependency list deciding whether the app compiles. Nine lines that cannot
 * disappear under a version bump is the smaller risk.
 */
private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener { continuation.cancel() }
    }
