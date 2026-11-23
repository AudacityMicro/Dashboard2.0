# Dashboard Windows widget — development preview

A native WPF companion for Dashboard 2.0, using the .NET Framework 4.8 already
included with supported Windows 10/11 installations. No Electron, embedded browser,
Google Drive for desktop or background server is bundled. Google sign-in opens the
system browser only during authorization.

Google sign-in, discovery of the existing phone dashboard, a Windows state export
and refresh-token reuse were verified on November 23, 2026. Physical microphone/camera
operation still needs device testing. Simultaneous task edits are covered by controlled
in-flight network tests and the paired Android revision/acknowledgment tests.

## Run

Extract the entire preview ZIP into a folder and run `Dashboard.exe`. Keep its DLLs,
configuration and licenses beside it. Drag the small top handle to move the
widget, drag the bottom handle up/down to resize its height, and use **⋮ → Settings** for background transparency
and optional always-on-top. Background alpha changes without fading the text.
There is no login requirement for local captures. The header has no title, capture
buttons use compact vector icons, and the main scrollbar is hidden while mouse-wheel,
trackpad and touch scrolling remain available. The bottom-right grip resizes width
and height together; completed drags save the window geometry.

Choose **Pin in place** from the menu to lock movement and resizing. Right-click
anywhere on the dashboard to open the same menu and unpin it. The pin survives
restarts and also blocks Windows' system-menu move and resize commands.

The app lives in the **system tray**, with no taskbar button. Right-click its tray icon
for Show/Hide, Refresh, Settings, **Start with Windows**, and Exit. Double-click the
tray icon or launch Dashboard again to reveal the existing instance. Closing the
widget hides it to the tray; **Exit** quits. Leaving a nonempty note still requires
confirmation and preserves the draft. `Dashboard.exe --exit` requests a normal exit
for updates, including any draft confirmation.

Startup is optional and runs as the current user at Windows sign-in, through one
`HKCU\Software\Microsoft\Windows\CurrentVersion\Run` entry named `Dashboard2.0`.
It does not install a service or require administrator access. Disable it from the
tray menu or Settings. Keep the extracted folder in its chosen location; if you move
it, re-enable startup from the new copy to update the saved path.

Features in this preview:

- Agent updates rendered as safe Markdown: headings, paragraphs, bold/italic, lists,
  quotes, code and HTTP(S) links. HTML is displayed as text, never executed.
- Task checkboxes, a minimal completed-task expander and offline task edits.
- Text notes with a local draft, a single Save action and confirmation when leaving
  a nonempty draft. Capture inbox previews text/images and plays recorded audio.
- Hold-to-record microphone button: grows while active; mouse capture tracks release
  outside the button. WAV PCM audio is retained locally. Ten-minute recording limit.
  A live input meter previews microphone levels while recording.
- Camera selection and preview inside the app, with one Take photo action. Camera
  access occurs only when opening this tool. No webcam/device is assumed available.
- Image/file uploads, limited to 25 MiB, with durable payload/manifest IDs and retry
  reconciliation so uncertain network outcomes do not create duplicate captures.
- Optional display of the phone's explicitly shared Health snapshot. Windows does
  not implement Health Connect or write health commands. Check the phone timestamp
  and date; downloading an old snapshot does not make it current.

Android app-launcher widgets and Android app lists are not Windows widgets. This
companion currently implements the dashboard/capture tools listed above. It is a
floating desktop window, not a Windows Widgets-board provider.

## Google connection

The phone's Android OAuth client cannot authorize a Windows app. Use a **Desktop
app** OAuth client in the **same existing Google Cloud project** as the launcher.
The maintainer's project is `snappy-attic-514121-g0`; other developers should use
their own project. Never commit the downloaded client JSON or user tokens.

1. In Google Cloud → Google Auth Platform → Clients, create a Desktop app client
   and download its JSON. Import it through the widget's Settings. Credentials are
   saved with Windows DPAPI for the current Windows user, not beside the executable.
2. Click **Sign in to Google** and approve the requested app access in your browser.
   The app uses PKCE S256, a cryptographic state value, and a temporary listener
   bound only to `127.0.0.1`. There is no embedded login form or password storage.
3. Sign in to the same Google account as the phone. Find the existing phone dashboard,
   or paste the compact setup prompt copied from the phone to choose exact file IDs.
   The requested Drive scope is `drive.file`, plus OpenID/email for account identity.
   Broad Drive scopes are not silently requested if file access is unavailable.
4. Update the phone to this branch's Preview 22+ before using shared task editing.
   Sync the Windows state, then copy its updated agent setup prompt. Guide version
   10 must be merged into the existing agent instructions, preserving custom edits.
   Its version-pinned release URL becomes available when Preview 22 is published;
   until then, review this branch's complete `AGENT_STARTER.md` directly.

Existing captures and file IDs stay pinned to the original Google account. A
different account is rejected; the app does not mix pending captures between users.
Disconnect stops future access and removes the active refresh token, with a durable
disconnect marker that also prevents backup recovery from reviving an old login.
An already-sent request may finish. Disconnect does not revoke Google's grant or
delete Drive files/history, captures or agent copies. Use Google Account controls
for grant revocation. That app-level revocation may also affect the phone.

The Windows state is `Dashboard-windows-<deviceId>-tasks.json`, separate from the
phone file. Windows never patches the agent board, phone state or instructions.
The agent reconciles all registered devices and acknowledges exact task revisions
in the shared board. Revisions are per-device, and stale acknowledgments cannot
erase a newer edit. Synchronization through the agent is eventual, not instantaneous.
See [the full agent contract](../AGENT_STARTER.md#windows-companion-and-device-task-edits).

## Voice modes and data

Settings supports transcript-only (keep audio local), original audio only, or both.
Transcription uses an installed **local Windows System.Speech recognition engine**;
availability, supported language and accuracy vary. It never falls back to an online
transcription service. In transcript-only mode, a failed/empty transcript leaves the
recording pending on the PC; retry from Capture inbox after installing/configuring
a supported local speech engine. In both mode, original audio can sync with an
explicit failed-transcription status. The agent may use its own authorized tools.

Private state, account identity, tokens, cached board/health data and edit receipts
are encrypted under `%LOCALAPPDATA%\Dashboard2.0\Windows\state.dat`, with an encrypted
backup. Original capture media is stored in the adjacent private user-profile
`captures` folder. It is not individually DPAPI-encrypted; use Windows account/disk
protection for media at rest. Do not share this folder or place it in the public repo.
Clear local data only after preserving unsynced captures. Uploaded data and agent
copies remain governed by Google Drive and the agent provider's controls.

## Build and test

**Check for updates** in either menu checks the latest GitHub release, displays its
brief patch note, and downloads and installs a newer Windows package. Size and
SHA-256 checks, a per-file manifest and strict extraction checks run before applying
the update. The helper waits for a normal app exit, backs up replaced files and
rolls back on installation failure. Captures and Google credentials stay in the
Windows profile. If installation fails, relaunch the existing copy after reading
the error. The update requires write access to the extracted app folder.

Run in PowerShell from the repository root:

```powershell
.\windows\build.ps1 -Test
.\windows\package.ps1 -BuildDirectory windows/bin -Destination windows/bin/Dashboard-Windows.zip
```

The script uses Windows' .NET Framework compiler and WPF assemblies. It restores
three pinned AForge 2.2.5 camera assemblies from NuGet and verifies package SHA-256
before extraction. Dependencies are dynamically loaded DLLs, not merged into the app.
The executable is `windows\bin\Dashboard.exe`. Use `-OutputDirectory windows/bin/staged`
to build without replacing a running test copy. `Tests.exe` uses synthetic data,
DPAPI round-trips, RFC PKCE vectors and an in-memory HTTP handler; it performs no
real Google authorization, Drive upload or microphone/camera recording.
Release packages include the update helper and hash manifest; use the packaging
script rather than zipping the executable alone.

For the Android task protocol, follow `DASHBOARD.md` to build both debug APKs. Run
`DeviceTaskEditsTest`, `DashboardStoreTest`, `DashboardSyncTest`, `HealthDriveSyncTest`
and `AgentPromptClipboardTest` with the instrumentation runner on an isolated test
emulator. Live Google access has been verified. Physical phone/PC concurrent editing and
microphone/camera operation still need device checks. Automated sync checks hold an
HTTP write in flight, edit another checkbox, replay stale receipts, and verify both
devices' choices remain durable. A Windows edit arriving during a request triggers
an immediate follow-up pass; sustained edits remain journaled for the next refresh.

## Licenses

Dashboard source is GPL-3.0. Unmodified `AForge`, `AForge.Video` and
`AForge.Video.DirectShow` 2.2.5 are LGPL-3.0, copyright AForge.NET 2006–2013.
License texts are in `licenses/`. Corresponding library source and build projects:
[AForge.NET upstream](https://github.com/andrewkirillov/AForge.NET). Package versions
and exact hashes are recorded in `build.ps1`. Users may replace the dynamically
linked camera DLLs with their own compatible builds. No FFmpeg component is used.

Primary platform references: [Google desktop OAuth](https://developers.google.com/identity/protocols/oauth2/native-app),
[WPF transparency](https://learn.microsoft.com/en-us/dotnet/api/system.windows.window.allowstransparency).
