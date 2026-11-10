# Publishing Dashboard updates

Start every published GitHub release body with one brief, user-facing sentence:

```text
What's new: Update checks now show a brief change summary before you choose to install.
```

Keep this logical line under 120 characters when possible. Describe the changes the
user will notice, rather than build details, checksums or internal implementation.
Longer notes and validation belong in subsequent paragraphs. The updater reads this
line from the selected release and displays it as plain text before installation.
It falls back to the first prose paragraph for older releases, caps summaries at
160 characters, and reports unavailable notes explicitly. Text can wrap to fit a
phone screen or larger font settings.

Continue publishing the signed `Dashboard-2.0-preview.apk` asset and its checksum
against the merged source commit. Verify the signing certificate, package/version,
GitHub asset digest and the permanent latest download link as described in
[DASHBOARD.md](DASHBOARD.md). Never replace a published APK in place or commit keys.

When changing the starter instructions, keep AGENT_STARTER.md identical to AgentSetup.instructions and update the compact prompt version and pinned STARTER_URL together. The public starter must contain no user-specific file IDs or credentials. The prompt tests enforce the public mirror and a compact handoff size.
