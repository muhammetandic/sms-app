# Google Drive backup — setup

The Drive backup is optional. With no Google Cloud project configured the app
builds, installs and works normally; the settings switch simply reports that
sign-in is needed. This page is the one-time setup that makes it work.

## Why there is no client ID in the source

`GoogleDriveAuth` asks for exactly one scope:

```
https://www.googleapis.com/auth/drive.appdata
```

Google lists that scope under **Non-sensitive scopes**, next to `drive.file`, so
it needs no sensitive-scope review and no CASA security assessment. Its
description — "view and manage the app's own configuration data in your Google
Drive" — is the whole of what it can reach.

Because the request carries only scopes and no server client id, Play services
authorizes the app against the **Android** OAuth client registered for this
package name and signing certificate, and it finds that client by itself. There
is nothing to paste into the code, which is why there is no constant to fill in.

The API offers no way to set a client id on its own: the only builder method that
takes one is `requestOfflineAccess(webClientId)`, which also asks Google for an
authorization code for a backend to redeem. There is no backend here, so that
would not be a smaller version of this request — it would be a different feature.
If one is ever added, that is the call to reach for.

## One-time setup

Do this in [Google Cloud Console](https://console.cloud.google.com/).

1. **Create a project** (or pick an existing one). Any project works; it does not
   have to be the one the app is published under, because the app is sideloaded.

2. **Enable the Google Drive API**: *APIs & Services → Library → Google Drive API
   → Enable*. Without this every call fails with a 403 whose message mentions the
   API being disabled.

3. **Configure the OAuth consent screen**: *APIs & Services → OAuth consent
   screen*. Choose **External** unless every user is in the same Google Workspace
   organization (then **Internal**, which skips the warnings below). Fill in the
   app name and support email. The scopes step needs no additions if the app is
   left "unverified" — the request is made by the app, not chosen from a list.

4. **Add two test users if the app is in "Testing"**: your own Google account at
   minimum. In *Testing*, grants expire after 7 days, so the app will ask to
   reconnect about weekly. Publishing the app (*Publishing status → Publish app*)
   removes that expiry and does not require Google review for this scope; an
   "unverified app" warning screen remains until brand verification is done,
   which is cosmetic for personal use.

5. **Create the OAuth client ids**: *APIs & Services → Credentials → Create
   credentials → OAuth client ID → Android*. Google identifies a sideloaded app
   by package name **and** signing certificate, so each signing key needs its own
   client. This app needs two:

   | Client name | Package name | SHA-1 |
   | --- | --- | --- |
   | SMS app (debug) | `com.anttech.smsapp.debug` | see below |
   | SMS app (release) | `com.anttech.smsapp` | see below |

   The debug client for this machine:

   ```
   Package name:  com.anttech.smsapp.debug
   SHA-1:         6C:24:CC:83:45:78:9F:96:0F:EC:B3:9D:99:01:F6:05:46:AA:7E:8E
   ```

   A debug keystore is per-machine and per-user, so read your own rather than
   trusting the value above. Nothing here is secret — a certificate fingerprint
   is public — but a mismatched one is the most common cause of failure.

   ```bash
   # Debug keystore (the password is always "android").
   keytool -list -v -keystore ~/.android/debug.keystore \
     -alias androiddebugkey -storepass android -keypass android | grep SHA1

   # The release keystore you sign with.
   keytool -list -v -keystore /path/to/release.jks -alias <alias> | grep SHA1
   ```

   Adding the release client is only needed once you install a release build.
   The debug and release app ids are different (`com.anttech.smsapp.debug` vs
   `com.anttech.smsapp`), so they never collide.

6. **Run the app** and open *Settings → Drive backup*, turn the switch on and
   approve the Google prompt. The status line should read "Up to date".

## What actually happens

Each device writes **one file of its own** into the account's hidden app data
folder, named `messages-<random-uuid>.jsonl.gz`. The uuid is generated locally on
first sync, so nothing about the phone, the SIM or the account goes into the
name. Sync then:

1. lists the folder,
2. downloads every file it has not read at that revision, merging the messages
   it does not already have,
3. writes its own snapshot back.

Merging is a union keyed on `dedupe_key`, which is already uniquely indexed, so
it is idempotent and order-independent: two phones merging the same message at
the same moment still store it once. A phone that is wiped and reinstalled
generates a new uuid, publishes a new file, reads the old one back, and the two
merge into one history.

The payload is gzipped NDJSON and is **not encrypted**. It sits in a folder only
this app can read, but someone with access to the Google account can read it
through the Drive API. If that matters, the alternative is to keep the backup off.

### The app data folder is invisible

`appDataFolder` does not appear in the Drive UI, cannot be searched, shared or
moved, and **cannot be trashed** — deleting is permanent. It is deleted when the
app is uninstalled, and its contents count against the account's storage quota.

So "I cannot find the backup in my Drive" is expected, not a failure. To confirm
it exists, read the status line, or:

```bash
adb logcat -s SyncWork
```

### What is not synced

- **Deletes and edits.** This version is append-only. Propagating them needs a
  tombstone record the schema does not have yet, and a merge that deleted
  anything missing from a peer's file would delete every message on a device that
  synced while the peer was mid-upload.
- **Conversation flags and unread counts.** Spam, pin, mute and archive are where
  the user filed things *on this phone*.
- **Drafts.**

Restored messages arrive with no unread badge and, for outgoing ones, a `SENT`
status: no delivery report for them ever reached this phone, so claiming
`DELIVERED` would assert something that did not happen.

## Quota

The Drive API bills each project in quota units: a list is 100, a download 200,
an upload 50, a metadata read 5. A routine sync — one list, no downloads, one
upload — is about 150 units. The default ceiling is 1,000,000 units per minute
per project, so a personal backup is nowhere near it. Upload bandwidth (1 TB per
day) is the only limit a large history could approach.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `ApiException: 10` (DEVELOPER_ERROR) | Package name or SHA-1 does not match a registered Android OAuth client, or the wrong project's client was created |
| `ApiException: 12500` | The OAuth consent screen has not been configured |
| 403 mentioning "has not been used in project" / "disabled" | The Google Drive API is not enabled on the project |
| Settings shows "Google Drive access is needed" | The grant was revoked or expired (with the consent screen in *Testing*, weekly) — tap Reconnect |
| Settings shows a failure line with a message | The message is Google's or the radio's, e.g. a timeout; it is retried by the periodic worker |

Nothing about a failed sync is destructive: the local store is never written from
a file that could not be read, and a file that failed to download keeps its
cursor so it is retried rather than skipped forever.
