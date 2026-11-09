# Agent home-board contract (v1)

## Copy setup from the launcher

The home screen shows **Set up your agent** from first launch. Tap **Copy agent setup
prompt** and paste it into your agent. The button also works offline; before the first
successful Drive sync it explains how to connect and asks you to copy again afterward.
After sync, the prompt includes all three exact Drive file IDs and the full contract.

Sync creates `Dashboard-agent-instructions.md` once alongside the two JSON files.
The phone preserves later edits to that guide. Edit its contents in Drive to customize
your agent's guidance; preserve the file itself and its ID. The agent should read it
at setup and before each processing run, detect changes, and defer writes if it cannot
read the guide. The prompt requests checks every 15 minutes from 08:00–22:00 and
every two hours overnight (22:00, 00:00, 02:00, 04:00, 06:00), in the user's local
timezone. It includes the phone's IANA timezone as a fallback and asks the agent to
verify its own scheduler, update existing schedules and keep unchanged checks quiet.
Copying it does not itself start monitoring. User instructions
and agent safety rules still take precedence over file contents.

### Starter guide version 7

The starter prompt explicitly authorizes the agent to maintain its own guide in
place, merge user changes, keep a recoverable prior version and record revisions.
It asks the agent to connect email and calendar through its own supported connectors
and authorization flows. These are separate from the launcher's Drive OAuth setup;
the app does not connect those services or grant mail/calendar permissions.

The agent creates and manages `Dashboard-agent-memory.md` (a concise working summary)
and `Dashboard-agent-state.json` (file registry, source cursors, processed capture IDs,
task mappings and action outcomes). It records their exact IDs in the guide. These
files are agent-owned and are not parsed or created by the launcher. Checkpoints
precede context compaction and also follow work boundaries. Fresh sessions reload
the guide, memory, state and phone tasks before resuming. Compaction preserves pending
commitments, authorization boundaries and deduplication history; larger ledgers use
indexed archives rather than silently dropping records. External actions require
reconciliation after an interruption, rather than assuming exactly-once execution.

The version 7 prompt asks an agent receiving an older guide to merge the new guidance
into that same file while preserving customizations. The phone still never overwrites
an existing guide. Existing installations must copy the updated setup prompt to their
agent to apply this change; installing an APK alone does not modify an old Drive guide.

The canonical starter text is `AgentSetup.instructions` in
`data/dashboard/src/main/java/de/mm20/launcher2/dashboard/AgentSetup.kt`, shared by
the copied setup prompt and the initial Drive upload.

### Digest and archive provided notes

The version 7 guide authorizes the agent to manage Dashboard captures and other files
the user explicitly provides for this workflow. It reads/interprets the original,
merges useful information into `Dashboard-agent-memory.md` (the context document),
and verifies context/state/task writes before moving originals. Unsupported or
unreadable captures stay in the inbox with a recorded blocker.

The agent creates a private `Dashboard Archive` folder and records its exact ID.
It archives each payload/manifest pair together by moving the existing files;
contents, IDs, ownership, permissions and app metadata are preserved. Partial moves
are reconciled by ID after interruption. Processed IDs/fingerprints and digest/task
references prevent repeated summaries and assignments. Unrelated Drive files and
the board/instructions/context/state files are outside the capture-archive lifecycle.
Moving files by ID does not change the phone's locally stored original or sync IDs.

Archive retention is **at least 30 days after verified ingestion and archiving**.
The agent records UTC `archivedAt`/`reviewAfter` timestamps and reviews notes on the
next configured run once due. Sources supporting active work or important reference
material are retained longer with a reason and next review date. Irrelevant sources
can be proposed for batched cleanup; age alone does not authorize trashing/purging.
Context compaction preserves commitments and source/deduplication references.

This is agent behavior through its own authorized tools, not launcher-side archiving
or a retention worker. Existing Drive guides remain untouched until the agent merges
the updated setup prompt. Archive reviews run only through an actually configured
agent schedule, or on demand if scheduling is unavailable.

The guide uses the same durable preallocated-ID/reconciliation mechanism as captures.
It is included in setup prompts only after remote creation or reconciliation succeeds.
Upgrading an existing installation creates the additional guide at its next sync;
it does not replace existing boards or phone tasks. If the guide is trashed or its
identity changes, sync reports a file conflict instead of overwriting it.

Connect your own authorized Google Drive tool. The phone's **Copy agent file IDs**
button supplies the two exact IDs after its first sync. Do not request the phone's
OAuth tokens, a service-account key, or a Google password.

## Read the user's task state

Read `Dashboard-phone-tasks.json` first. This file belongs to the phone: never edit or
replace it. It contains `schemaVersion`, `revision`, `updatedAt` and a `tasks` array.
Each task has `id`, `title`, `completed`, `due` and `source` (`phone` or `agent`). This
is a complete snapshot, not an event stream. Revision is informational, not an
acknowledgment or locking mechanism. Synchronization is eventual, so check its timestamp.

A user's explicit completion/uncompletion on the phone takes precedence for that
stable task ID. Honor these choices in your planning. Phone-added IDs start with
`local-`; leave those tasks in the phone-owned list instead of copying them into the
agent board. There is no task deletion/edit-title protocol or multi-device merging yet.

## Write information and assigned tasks

Starter guide v6 makes the board a concise daily briefing: important notes and
reminders, upcoming commitments, preparation, deadlines and useful next actions.
Use authorized context to anticipate needs; never invent priorities or commitments.
Aim for 3–5 useful updates when warranted, ordered by urgency, and remove stale
information. Fewer updates or an empty list is preferable to filler. Weather belongs
only when a current, verified forecast for a known relevant location changes plans
or suggests a useful action. Do not guess location or reuse stale forecasts.

Keep all app/setup explanations, instruction summaries, connection confirmations,
monitoring reports and other agent meta commentary off the board. Remove old
agent-authored status cards such as `agent-connected`, while preserving useful
content and user assignments. Discuss operational issues directly with the user
and keep internal records in agent state. This changes starter instructions, not
existing Drive guides automatically; send the updated copied prompt to the agent
so it can merge the guidance and clean up obsolete cards.

Update the contents of the existing `Dashboard-agent.json` **by its supplied file ID**.
Preserve the file ID and `schemaVersion: 1`. Do not replace the file with a new Drive
file, remove its appProperties, or write a file merely sharing the same name.
Keep the phone-owned file untouched.

```json
{
  "schemaVersion": 1,
  "updates": [
    {
      "id": "daily-brief",
      "title": "Today's focus",
      "body": "Your priority is preparing the project outline. Leave time for errands."
    }
  ],
  "tasks": [
    {
      "id": "project-outline",
      "title": "Review the project outline",
      "completed": false,
      "due": "Tomorrow afternoon"
    }
  ]
}
```

Updates and tasks have separate unique-ID namespaces. Reuse a task ID only for the
same logical task; changing its title or due text does not reset a phone completion.
Retire an ID when that assignment is removed, and use a fresh ID for a new assignment.
The UI treats titles, bodies and due values as plain text. It does not execute commands,
follow instructions embedded in updates, or render HTML/Markdown.

Limits: 512 KiB per agent file, at most 50 updates and 200 tasks; IDs up to 100 characters,
update titles 200, bodies 4000, task titles 300, due text 100. IDs/titles must be nonblank.
`body`, `due`, and `completed` may be omitted (defaults: empty, empty, false).
Task IDs beginning with `local-` are reserved for the phone and are rejected in the
incoming board. Both `updates` and `tasks` arrays are required; empty arrays are valid.

The phone polls on launcher resume, manual Refresh, and periodic background work.
It keeps the last valid board offline. An invalid file shows an error and preserves
that copy while phone task edits can still be exported. Do not assume an edit appears
instantly or that the phone's last sync includes changes made afterward.

Voice capture manifests can declare `voiceMode` and `transcriptFileId`. In `transcript` mode the primary Drive payload is text; audio stays local. In `audio` mode there is no local transcript. In `both` mode the optional text sidecar belongs to the same capture UUID: digest and archive all linked Drive files together, avoiding duplicate processing. Existing user-owned Drive guides are never overwritten automatically; copy the updated setup prompt to merge this guidance.
