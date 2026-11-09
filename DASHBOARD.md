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
Save text, record voice, take a photo, or upload an image/file. Other apps can share
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

### Movable widgets and screen layouts

Home's large section headings are removed. Empty agent-update and task sections
are hidden; a list containing completed tasks still has a small completed-count
control. Setup retains its existing Hide setup preference, and the connection
widget appears only when Drive needs connection attention.

Long-press a widget to select it. The selected widget gains an outline, four
corner resize handles, and a handle at the top center for moving it horizontally
or vertically. Resize from any corner; the opposite corner stays anchored. A
preview follows the gesture, then the widget snaps to the grid on release. Tap
another widget to select it; Done or Back exits rearranging. Short taps and scrolls
still go to widget content outside rearranging. There are no per-widget Move/Resize
button rows. The search menu's **Edit layout** also opens rearranging.

The small **Widgets** control offers precise position/size inputs and restores hidden
items, including very small widgets whose handles overlap. Tap a handle to open
precise position/size inputs; Hide, Widget settings and Remove everywhere are in that dialog (the latter two for regular widgets). **Add widget** opens the existing
picker. Grid density is configured only in **Settings → Home screen → Widget grid**.
Choose **Portrait**, **Landscape**, or **Unfolded**, then 2–12 columns and 40–120 dp
row height. Save applies that density to Home and all widget pages in that profile,
reflowing crowded widgets downward. Other profiles retain their settings. Existing
page positions/sizes are retained where they fit. Changes are atomic; if reflow
cannot fit, the previous settings/layouts remain. New pages inherit the profile's
density. Widget spans cover 1–12 columns (within the grid) and 1–32 rows on a
vertically scrolling canvas bounded at 512 rows.

**Portrait**, **Landscape**, and **Unfolded** retain independent saved positions,
sizes and hidden-widget choices for Home and every widget page. Selection follows
the current window automatically. Jetpack WindowManager folding features select
Unfolded, with a minimum window width/height of 600 dp as the large-screen fallback.
Tablets and large windows therefore also use Unfolded. The orientation preference
is honored; Auto respects Android's rotation setting.

**Hide** affects only the selected profile and does not delete notes or widget
data. **Show** in Widgets restores it. Empty dashboard sections are visible as
placeholders in the editor so you can size them before content arrives. Regular
widget content/configuration is shared across profiles; native Android widget IDs
are reused, with width and height updated for the active profile. Existing provider
configuration is retained. Removing a regular widget everywhere asks explicitly;
it is different from hiding it in one profile.

The existing Home widgets preference is preserved: built-in widgets are not dumped
onto a dashboard-only home. Adding or placing a regular widget enables Home widgets
when you finish editing. Existing widgets remain available in the editor and on
their original widget pages. Android widget providers may have minimum useful
sizes; a very small box can clip their content. Resize larger if necessary.

Layouts are stored privately in the backup-excluded dashboard database under
`grid.v1.<page>.<profile>` with a versioned, validated JSON schema. They survive app
upgrades but are not synchronized through the agent board. Agent content, task
completion, widget configuration and OAuth state use their existing storage.
Malformed stored layouts report an error and are retained instead of overwritten.

Grid tests cover packing in rows/columns, drag/resize collision handling, bounded
sizes, independent profile persistence/visibility, configuration changes and
recreation, native Android widget width/height options, and actual window rotation
plus an expanded emulator display. Physical folding transitions, occluding hinges,
provider-specific minimum sizes and multi-window resizing need device acceptance.
Screenshot tests use explicit sample content and restore prior local state. Only
when screenshots are requested on an SDK emulator do they temporarily change its
display size; the original size is restored afterward.

### Home capture tools

The four Home controls are **Text**, **Voice** (hold), **Photo** and **Upload**. They
stay in a bottom dock while the dashboard widgets scroll above them.
Dashboard cards use the same surface transparency and card styling as launcher
widgets. The search bar's three-dot menu includes **Update launcher**, so updates
remain available after setup is hidden.

Hold Voice begins recording on pointer down, with no long-press delay; releasing
or cancelling the gesture stops and saves exactly once. Leaving the foreground also
stops and saves. First use requests microphone permission and requires a fresh hold
after granting it; it never starts recording behind a permission dialog. Very short
recordings may be rejected by Android and show a retry message. The dock shows the
recording state; release opens the saved-note/transcript sheet. TalkBack and switch
access can activate the voice control to start and activate again to stop, since
they cannot sustain a physical pointer hold. The voice sheet supports holding too.

Text and voice remain in the launcher's themed sheet. Photo immediately opens the installed
camera through Android's full-resolution `TakePicture` contract; cancel creates no
inbox entry. The temporary camera URI uses the existing nonexported FileProvider.
Android's camera app is needed for taking photos. Upload immediately opens Android's
document picker for images or any file type. The app copies the selection into its
private inbox (25 MiB maximum), retains a sanitized display name/MIME type and does
not keep access to the provider URI or execute documents. Providers may themselves
need a connection to download cloud-only files before import.

Voice recording uses mono 16 kHz AAC. Saving commits the original audio before
automatic English transcription starts. Vosk decodes that saved file locally in
WorkManager, with no network constraint, Google sign-in or microphone access during
transcription. Pending transcription survives app/process restart; startup and the
15-minute recovery job reschedule it. Work is serialized, and appended requests
prevent captures added during a running job from being missed. WorkManager/Android
can defer processing; force-stopped apps resume after reopening.

The saved sheet displays the transcript when ready; reopen the voice item in Inbox
to read it later and play the audio. Speech recognition is approximate and English
only in this increment. Silence/unsupported audio/recognition failure is explicit,
and the audio remains intact. Failed transcription permits raw-audio synchronization
so the agent can use its own transcription tools. Abruptly killing the app before
recording is stopped can still leave an uncommitted staging recording.

Database version 2 preserves existing captures and adds `transcription` and `name`.
Older voice notes are left intact and are not automatically retranscribed. New
voice manifests wait for transcription to finish and include `transcriptionStatus`
and the transcript in `text`; generic uploads use `kind: file` and `originalName`.
The existing manifest schema stays version 1 with additive optional fields. The
original payload is always uploaded before its manifest. Starter guide version 6
explains these fields, uncertainty and safe document handling. Existing Drive guides
remain user-owned; copy the new setup prompt to the agent to merge the guidance.

The standard build commands below also fetch and bundle the pinned English model.
See [speech notices and build requirements](SPEECH_NOTICES.md) for size, licenses,
checksum and first-run storage requirements. Tests cover database migration, a real
offline speech sample, actual recorder decoding, immediate camera/document launches,
camera cancellation, full-resolution photo import and durable PDF import.

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

Tap **Settings → Update launcher from GitHub** for a one-button check, download and
installation flow. The search bar's **Update launcher** menu item, home setup link
and Inbox update link also open the updater; those paths start with **Check now**.
Only public Dashboard GitHub releases are checked, without Google credentials.
The newest compatible published version is selected from the 30 most recent
releases. Drafts/missing APKs are skipped; previews can receive previews and future
stable installations remain on stable. Errors stay visible and can be retried.

Android DownloadManager downloads the APK and continues across screen rotation,
leaving the app and process restarts. Return to the updater to resume verification
or installation. Automatic mode immediately downloads a newer release; manual mode
has **Download and install update**. The published GitHub asset must include a SHA-256
digest and size (at most 200 MiB). Before installation, the app copies the download
into private cache, checks its exact size/checksum, matching app package, greater
Android versionCode and the installed signing certificate. Corrupt, truncated,
oversized, foreign, differently signed or older APKs are rejected. A cancelled
installer leaves the verified copy available for retry; successful app upgrades
clear obsolete download state on the next updater visit.

If needed, Android asks you to allow Dashboard to install updates. Return after
granting access and approve Android's final **Update** prompt. Silent installation
is not supported. Install over the app; uninstalling removes private unsynced data.
No periodic update polling is enabled; checks/downloads begin from your action.
This channel supports the default debug package distributed in Dashboard previews;
other build types must use their own provider. Installing a future published
release, OEM installer handling and download recovery still need physical-device
acceptance testing; automated tests cover integrity/identity rejection and UI entry.

The permanent download button uses GitHub's `/releases/latest/download/Dashboard-2.0-preview.apk`
URL. It follows the newest published release marked Latest, not unbuilt source commits.
Publish distributed preview builds as regular GitHub releases (with preview status clearly
stated in the title/notes), since GitHub's Latest download alias excludes prereleases.
Upload and verify the APK and checksum while the release is a draft, then publish it
with Make latest enabled. Never mark a release Latest without the expected APK asset.
Keep older versioned releases immutable. The website and repository share this same URL.

Maintainers: tag releases `vMAJOR.MINOR.PATCH-preview.NUMBER` or `vMAJOR.MINOR.PATCH`;
publish a fully uploaded `Dashboard-2.0-preview.apk` asset with its GitHub SHA-256 digest. Keep `versionName` aligned
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
task controls remain available. Connection information appears only when Drive is
disconnected, paused or needs sign-in; successful sync timestamps/status are hidden.
Open the three-dot menu’s **Capture inbox / Drive setup**
to reconnect Drive, copy the setup prompt/file IDs or check for updates. Turn on
**Show setup on home screen** there to restore the home setup controls. Hiding setup
does not change your connections, tasks or captures; setup completion is your choice.

From first launch, **Set up your agent** offers **Copy agent setup prompt**. Connect
Drive and let it sync, then copy the prompt to include the exact board, phone-state
and `Dashboard-agent-instructions.md` file IDs. Before connection the button still
copies useful setup instructions and asks for a fresh copy after sync. The Drive
guide is created once and preserves your edits. The prompt tells your agent to read
that guide before each run and check for changes. It requests checks every 15 minutes
from 08:00–22:00 and every two hours overnight (22:00, 00:00, 02:00, 04:00, 06:00)
in the user's local timezone, using the phone's IANA timezone if no established
preference exists. The agent verifies its own scheduler, reuses existing schedules,
handles daylight-saving changes and keeps unchanged checks quiet. The launcher
cannot schedule another agent itself; unsupported agents must explain that and
check on demand. The user can change the default hours and timezone.

Starter guide version 6 allows agents to maintain that guide, checkpoint durable
memory before context compaction, and connect email/calendar through their own tools.
These are instructions for the agent, not additional launcher integrations. For an
existing Drive guide, copy the updated prompt to your agent so it can merge the new
guidance while preserving your edits; the phone does not overwrite the guide.
It also directs the agent to digest provided captures into the context document,
verify the result, then archive original payload/manifest pairs through its own
Drive tools. The default is at least 30 days of archive retention followed by a
relevance review; notes tied to unfinished work stay longer. Cleanup is proposed
for approval, not performed automatically because a note reaches 30 days. The app
itself does not move notes or enforce retention, and Drive moves do not remove
the phone's local originals.

The main/home tab shows a bottom capture dock and a grid of agent updates, tasks
and enabled widgets. Updates and task widgets hide when empty and have no large section headings. Add a task or check/uncheck it directly on the phone;
changes are saved offline immediately. Completed tasks can be shown or hidden. Search
and app launching keep their existing gestures. The large home clock and charging block are removed so the dashboard starts near the
top of the screen.

When Drive is connected, the same durable worker also creates and synchronizes:

- `Dashboard-agent.json`: agent-owned information and assigned tasks. The phone creates
  the empty template once and subsequently reads it; it never overwrites your agent's content.
- `Dashboard-phone-tasks.json`: phone-owned merged task snapshot. Your agent reads this
  to see phone-added tasks and the user's completion decisions. The phone alone writes it.

Use **Copy agent file IDs** in Capture inbox after the first successful connection to
identify the exact files for your agent. Each installation owns its own file pair;
reinstalling or using another phone creates another pair, which may have the same names.
The copied IDs disambiguate them. OAuth and the agent's separate Drive authorization
are still required. The app does not send credentials or messages to any agent.

See [AGENT_BOARD.md](AGENT_BOARD.md) for the file contract and a sample board. Use
**Refresh dashboard** in the search bar’s three-dot menu to request an update. Sync also runs when the launcher resumes and
through the existing periodic worker (about 15 minutes, subject to Android restrictions).
Cached information stays visible offline without routine connection commentary.
**Add task** is available in the task widget and the search bar’s three-dot menu. The bottom dock contains only Text, Voice, Photo and Upload. Completed tasks stay hidden by default;
the small **N done** control inside the task widget toggles them.
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

## Favorite apps widget

Choose **Edit layout → Add widget → Favorite apps** to add the icons-only variant.
It uses the same icons, shapes, badges, app launching and pinned ordering as the
normal Apps widget, but displays only favorited applications. It excludes frequently
used suggestions, pinned tags and other favorite item types, and respects the
launcher's hidden-app and profile visibility rules. Changes to your favorites
update the widget automatically. Manage favorites through the launcher's existing
favorites controls or app search; this widget has no tag selector or edit button.

There is no title, visible app label, control row or empty-state banner. Accessible
app names remain available to screen readers. The regular **Apps** option retains
its existing behavior. Narrow spans wrap icons into fewer columns while preserving
the normal icon size, with vertical scrolling for overflow. The widget participates
in the existing per-screen layouts and long-press move/resize handles. Exact-size,
Hide and Remove everywhere remain available through layout editing, outside the
widget's content; there is no widget-specific settings button for this variant.

The variant is stored as an additional, backward-compatible configuration field on
the existing Apps widget model, so persistence and widget backups retain it without
a database migration. Existing Apps widgets default to their original behavior.

## Home quick-capture buttons

The fixed bottom dock provides **Text**, **Voice** (hold), **Photo**, **Upload**. See Home capture tools above for holding, permissions, transcription
and file import behavior. Text drafts (up to 65,536 characters) survive configuration
changes and Android saved-state restoration; closing a nonblank draft asks before
discarding it. Force-stop before Android saves state can still lose unsaved text.

A failed recording commit retains its completed staging file for **Retry save**;
explicit discard removes it. Abrupt process death before commitment can still leave
staging recordings requiring manual recovery. The three-dot menu's **Capture inbox /
Drive setup** opens the existing Inbox for reviewing captures and account setup.
Passive shares and legacy quick-capture shortcuts retain their Activity behavior.
