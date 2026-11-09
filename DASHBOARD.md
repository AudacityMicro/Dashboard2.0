# Dashboard capture launcher

Dashboard2.0 extends the GPL-3.0 Kvaesitso Android launcher. Upstream base:
`MM2-0/Kvaesitso@c4805f3f18d5bcf071c5287f81466efd79169b20`.
The full upstream source, history, LICENSE and copyright notices are retained.
Existing search, app launching, widgets and launcher settings are retained. The home tab
adds Agent updates and To-do list cards. The large clock and charging block are removed
from the home content, leaving the Android status bar indicators.

## Use

Install the default debug APK, choose Dashboard as your Home app, then search for
**Dashboard Inbox**. You can also long-press Dashboard's icon and pin **Capture inbox**.
Save text, record voice, or select an image/audio document. Other apps can share
text, images or audio into Dashboard Inbox; shared text remains a draft until saved.
Tap an inbox entry to read, preview or play it. Recording stops and saves when the
activity leaves the foreground; there is no background microphone service.
Imports and recordings are limited to 25 MiB; recordings also stop after 10 minutes.
The latest 100 captures are shown, and all captures remain stored locally.

Capture works without Google or a network connection. Connect Google Drive when
ready. Status is `pending`, `synced`, `auth_required`, or `failed`. Retry after fixing
an authorization/configuration problem; transient upload errors retry automatically.
Pause Drive sync to stop starting further uploads (an already in-flight request may
finish). Pause does not revoke Google's grant. Revoke it in your Google Account's
third-party connections settings if desired. Reconnect the same account to resume.
Changing accounts is deliberately blocked while the inbox remains bound to its first
account, including already synced captures. No captures are deleted automatically.

## Google OAuth setup (required once by the app maintainer)

1. Create/select a Google Cloud project, enable Google Drive API and configure its
   OAuth consent screen. For testing, add the intended Google accounts as test users.
2. Register an **Android OAuth client** for the installed package and signing SHA-1:
   - debug: `io.github.audacitymicro.dashboard.debug`
   - release: `io.github.audacitymicro.dashboard.release`
   Use `./gradlew :app:app:signingReport` to find the debug fingerprint. A downloaded
   CI debug APK is signed with that runner's debug certificate; register its SHA-1 or
   build locally with your stable debug key. CI keys are not suitable for releases.
3. Request only `drive.file`, `openid`, and `email`. Complete any Google production
   consent/verification requirements before distribution. Testing-mode consent may
   expire; reconnect if asked. Google Play Services must be available on the device.
4. Open Inbox and select **Connect Google Drive / retry sign-in**, then grant consent.

No client secret, service-account key, OAuth refresh token or user password belongs
in this repository or in the app's text fields. Android OAuth clients are identified
by package/signing certificate; no secret entry screen is needed. Google Play Services
manages reusable credentials in its protected account storage. Dashboard only holds
short-lived access tokens in process memory. Tokens are never persisted in app files,
preferences, SQLite, logs or Android backup. The non-secret account subject and pause
flag live in an AtomicFile under `noBackupFilesDir`. No app-level token vault exists
because the application never stores a token. Do not add one unnecessarily.

## Drive format for agents

Uploads use the visible **My Drive root**, not the hidden `appDataFolder`, with the
least-privilege `drive.file` scope. Each capture produces:

- `Dashboard-<uuid>.txt`, `.audio`, or `.image`: original bytes and correct MIME type.
- `Dashboard-<uuid>.json`: UTF-8 record with `schemaVersion`, `id`, `createdAt` (UTC),
  `kind`, `mimeType`, `text`, `driveFileId`, and `source`.

Example manifest:

```json
{
  "schemaVersion": 1,
  "id": "a-capture-uuid",
  "createdAt": "2026-11-08T20:00:00Z",
  "kind": "voice",
  "mimeType": "audio/mp4",
  "text": "",
  "driveFileId": "google-drive-file-id",
  "source": "Dashboard2.0"
}
```

An agent can discover files by the `Dashboard-` name prefix, then read the JSON and
follow `driveFileId`. Dashboard's private `appProperties` additionally record source
and capture UUID for its own reconciliation. Authorize the agent's own Drive
connection separately; this launcher does not share credentials with Dot/Muse or
silently grant access to another application. No transcription, image OCR, AI calls or direct Dot/Muse API integration is implemented.
Capture files are immutable; the home board has its own agent/phone file exchange below.
Manifests are created only after the payload upload is confirmed. Treat the manifest
as the completion marker and ignore payload-only partial uploads until retry.

## Durability and security design

Files are copied into app-private no-backup storage, flushed, renamed, then entered
into SQLite. A failed import never creates a queue entry. The database and payloads
are colocated under `noBackupFilesDir`, so Android cloud/device-transfer backup cannot
restore a database whose payloads or credentials are missing. Device loss/uninstall
before sync loses local data. Disk contents rely on Android sandbox/device encryption;
there is no additional per-note application encryption.

WorkManager uses network constraints, exponential backoff, a single unique worker
and a 15-minute periodic recovery enqueue. Enqueues coalesce while a worker is pending
or running, preventing unlimited work-chain growth while offline. A capture saved
as a worker finishes its snapshot may wait until the periodic recovery. Startup also schedules recovery.
Android background restrictions can delay work; force-stop requires reopening the app.
Every Drive ID is generated and committed locally **before** upload. Retries first
check the same ID, so network loss after remote creation cannot produce a duplicate.
Payload and manifest use independent stable IDs. A sync is complete only after both
exist. Deleting or editing a synced Drive file does not trigger re-upload or change
the local original. Permanent errors remain visible and repairable; local files stay.

The first authorization binds the inbox to Google's stable subject identifier.
Each capture is bound before its first upload. Workers validate Google's current
subject before uploading, avoiding accidental cross-account retries. Auth errors
pause affected queue entries until foreground reauthorization. Network, quota,
rate-limit and server failures remain pending. All HTTP 403 responses currently use
backoff because they may represent quota/rate limits; reconnect or fix Cloud setup
if a persistent permission/configuration error prevents progress.

Crash between file rename and DB insertion may leave an unindexed file; interrupted
recording during abrupt process death can leave an unfinished recording. These are
not uploaded and are retained in private storage. Crash cleanup/recovery, deletion,
full inbox pagination, camera capture and richer launcher widgets are follow-up work.

## Build and tests

Use JDK 21, Android SDK Platform 37.1, and the checked-in Gradle 9.6 wrapper. The
inherited upstream dependency catalog pins Android Gradle Plugin 9.4.1. Android Studio
can configure/download the required SDK. Set `JAVA_HOME` and `ANDROID_HOME`, or set
`sdk.dir` in ignored `local.properties`. Do not commit that file, keystores or secrets.

```sh
./gradlew :app:app:testDefaultDebugUnitTest :app:app:assembleDefaultDebug
# Windows: .\gradlew.bat with the same arguments
# On a disposable emulator/device (integration tests write temporary inbox entries):
./gradlew :app:app:connectedDefaultDebugAndroidTest
```

Check that the connected-test report contains a nonzero test count. With the current
Android 17 emulator/toolchain, Gradle may report success with zero discovered tests.
In that case, install both built APKs and invoke the runner directly (use your SDK's
`adb`, and `adb -s <serial>` if multiple devices are connected):

```sh
adb install -r app/app/build/outputs/apk/default/debug/app-default-debug.apk
adb install -r app/app/build/outputs/apk/androidTest/default/debug/app-default-debug-androidTest.apk
adb shell am instrument -w io.github.audacitymicro.dashboard.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Require a nonzero `OK (... tests)` result, rather than a shell exit code alone. Allocate at least 4 GiB
RAM to the Google-enabled test emulator to avoid startup memory pressure interrupting
the test process. Test only on a disposable device/emulator.

APK: `app/app/build/outputs/apk/default/debug/app-default-debug.apk`.
Unit tests cover retry classification and account isolation. Instrumentation tests
cover local persistence across DB reopening, stable Drive ID/account retention on
retry, Unicode manifest data, failed imports, blank-note rejection, lost-response upload reconciliation, remote-ID
conflict rejection, and saving through the UI while preserving the Home activity. The new CI
workflow builds/tests a debug APK without secrets. Upstream scheduled publishing and
signing workflows are removed from this custom branch. Release builds are unsigned until you provide your own release signing configuration.
The inherited `fdroid` flavor now also includes Google authorization and is not
suitable for F-Droid distribution; this increment targets `default` builds.

Manual acceptance on a disposable Google-enabled emulator/device:

1. Enable airplane mode, save text, image, and voice; restart app/device and preview.
2. Deny microphone permission and cancel picker/sign-in; verify existing notes remain.
3. Connect a configured test Google account, enable network, wait for both files and
   `synced`; inspect JSON through a separately authorized agent/Drive connection.
4. Interrupt network/process during upload, reopen, retry and verify one payload +
   one manifest per UUID, including interruption after payload creation.
5. Revoke the Google grant, capture again, verify `auth_required`, then reconnect.
6. Try another Google account; verify queued data is not sent to it.
7. Confirm home search, app launching, shortcuts and widgets still work.

Live OAuth consent, Drive writes, reboot/network fault injection and launcher gesture
regressions require device validation; do not equate a compile with these checks.

## Primary API references

- [Google Android authorization](https://developer.android.com/identity/authorization)
- [Drive pre-generated IDs](https://developers.google.com/workspace/drive/api/guides/manage-uploads#use_a_pre-generated_id_to_upload_files)
- [AndroidX Test release compatibility](https://developer.android.com/jetpack/androidx/releases/test)

## Dashboard updates

Tap **Check for updates** on the home setup card or **Check for Dashboard updates**
in Capture inbox, then **Check now**. This manual check contacts the public GitHub
releases API without Google credentials. It examines the 30 most recent published
releases, skips drafts/missing APKs and compares Dashboard versions numerically.
Preview builds include preview releases; a future stable build stays on stable.
Offline, rate-limit and server errors remain visible and can be retried.

**Download update** opens the official repository's APK URL in your browser. Open
the downloaded APK and approve Android's update prompt. Android verifies the installed
package's signing certificate. Install over the app; uninstalling removes private,
unsynced data. No background checks, automatic downloads or silent installs occur.
This channel supports the default debug package distributed in Dashboard previews;
other build types must use their own provider.

Maintainers: tag releases `vMAJOR.MINOR.PATCH-preview.NUMBER` or `vMAJOR.MINOR.PATCH`;
publish a fully uploaded `Dashboard-2.0-preview.apk` asset. Keep `versionName` aligned
with the tag and increment Android `versionCode` for every distributed build (including
CI overrides). Continue signing published updates with the same private signing key.
CI's temporary debug keys cannot update the locally signed preview installation.
Preserve the maintainer's key securely outside the public repository. Never replace
an existing version's asset with different code. Existing Preview 1/2 installations
need one manual installation of Preview 3 or newer to gain this feature.

Release API: https://docs.github.com/en/rest/releases/releases

Completed recordings are retained as private staging files if committing the inbox
fails (for example, when storage is full), rather than deleting the original.
Staging-file recovery is not yet exposed in the UI.

## Home tab: agent updates and to-dos

Tap **Hide setup** on the home setup card whenever you are ready to clear the setup
controls from Home. The choice persists across launcher restarts and app updates.
It hides the setup card and file-ID button, while capture actions, agent updates,
task controls and operational sync status remain available. Open **Capture inbox**
to reconnect Drive, copy the setup prompt/file IDs or check for updates. Turn on
**Show setup on home screen** there to restore the home setup controls. Hiding setup
does not change your connections, tasks or captures; setup completion is your choice.

From first launch, **Set up your agent** offers **Copy agent setup prompt**. Connect
Drive and let it sync, then copy the prompt to include the exact board, phone-state
and `Dashboard-agent-instructions.md` file IDs. Before connection the button still
copies useful setup instructions and asks for a fresh copy after sync. The Drive
guide is created once and preserves your edits. The prompt tells your agent to read
that guide before each run and check for changes. Agree a monitoring schedule with
your agent; the launcher cannot schedule another agent itself.

Starter guide version 2 allows agents to maintain that guide, checkpoint durable
memory before context compaction, and connect email/calendar through their own tools.
These are instructions for the agent, not additional launcher integrations. For an
existing Drive guide, copy the updated prompt to your agent so it can merge the new
guidance while preserving your edits; the phone does not overwrite the guide.

The main/home tab now shows quick-capture buttons, **Agent updates** and a **To-do list**,
plus any enabled home widgets. Add a task or check/uncheck it directly on the phone;
changes are saved offline immediately. Completed tasks can be shown or hidden. Search
and app launching keep their existing gestures. The large home clock and charging block are removed so the dashboard starts near the
top of the screen.

When Drive is connected, the same durable worker also creates and synchronizes:

- `Dashboard-agent.json`: agent-owned information and assigned tasks. The phone creates
  the empty template once and subsequently reads it; it never overwrites your agent's content.
- `Dashboard-phone-tasks.json`: phone-owned merged task snapshot. Your agent reads this
  to see phone-added tasks and the user's completion decisions. The phone alone writes it.

Use **Copy agent file IDs** on the home card after the first successful connection to
identify the exact files for your agent. Each installation owns its own file pair;
reinstalling or using another phone creates another pair, which may have the same names.
The copied IDs disambiguate them. OAuth and the agent's separate Drive authorization
are still required. The app does not send credentials or messages to any agent.

See [AGENT_BOARD.md](AGENT_BOARD.md) for the file contract and a sample board. Refresh
on the home card to request an update. Sync also runs when the launcher resumes and
through the existing periodic worker (about 15 minutes, subject to Android restrictions).
The card displays the last successful sync time; cached information stays visible offline.
Phone-added tasks and explicit completion overrides survive all incoming refreshes.
Stable task IDs identify the same task; give a genuinely new task a new ID.

Incoming JSON is limited to 512 KiB, 50 updates and 200 assigned tasks, with bounded
plain-text fields and unique IDs. Malformed/unsupported data never replaces the last
valid board, and does not prevent exporting phone task edits. Phone overrides take
precedence for task completion until that task ID is retired by the agent. The first
version supports task additions and completion/uncompletion, rather than task deletion,
renaming, rich-text messages, notifications or a multi-device merge protocol.

Added integration tests cover incoming refresh vs phone overrides, invalid-board
retention, exported phone snapshots, and saving/checking off a task on the actual
launcher home screen. Live Drive registration remains a prerequisite for end-to-end
Google testing; transport integration tests use a local fake server without credentials.

## Home quick-capture buttons

The home tab provides **Text note**, **Voice note**, and **Add image** directly above
Agent updates. Text note opens the focused editor and keyboard; Voice note requests
microphone permission when needed and starts recording; Add image opens the system
image picker. Picker cancellation leaves existing data unchanged. These automatic
capture actions use a non-exported activity, so external share intents cannot trigger
an automatic microphone recording. The regular Inbox entry remains available for
reviewing captures and configuring Google Drive.
