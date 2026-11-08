# Agent home-board contract (v1)

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
