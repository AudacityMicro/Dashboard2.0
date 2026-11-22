# Health Connect

Add **Health** from the launcher's widget picker. Resize/move/delete it with the
same long-press grid editor as other widgets; it follows launcher transparency.

1. Open **Settings → Health Connect → Connect / calorie permissions**. Grant the
   nutrition and calorie permissions you want. Android 14+ includes Health Connect;
   supported older phones may need Google's Health Connect app. Other launcher
   features continue working if Health Connect is unavailable or denied.
2. Choose additional health stats if desired. Permissions remain granular. Optional
   background reads/history access depend on device support and user approval.
3. Connect your own Google Drive. Separately enable **Share permitted health stats**
   and/or **Allow my agent to log food and change calorie goals**. Both default off.
4. Sync, copy the short agent setup prompt, and give it to your agent through its
   own Drive connection. Existing agents must merge guide version 9 from the pinned
   starter into their current instructions, preserving user edits.
5. Tell your agent your chosen daily food goal. No goal is assumed by the app.

Remaining = goal − recorded food kcal + active kcal burned (activity credit enabled
by default). **Burned** shows total calories separately; resting calories are never
added to remaining. Missing permission/data shows a dash, not a fabricated value.
The widget reads its local cache first, refreshes off the UI thread, and labels
freshness. Background work/sync is best-effort and can be delayed by Android.

## Agent contract

The complete schema, examples, retry rules and consent requirements are in
[AGENT_STARTER.md](AGENT_STARTER.md#optional-health-connect). The existing phone
state exposes `health.agentFileId` and `health.phoneFileId`. The agent edits only
the former; the phone publishes acknowledgments and permitted data in the latter.
Food writes need stable IDs/revisions. Correction = same ID, larger revision;
removing a command does not delete a meal. All input is validated before commands
run. Only `WRITE_NUTRITION` is requested; all other supported records are read-only.

SDK 1.1.0 supports 41 fitness/wellness record classes, filtered by device features
and user permissions. This is not access to clinical/FHIR medical documents or
exercise route consent flows. Stats are requested in bounded, paginated windows,
not a continuous full-history dump. Queries need fresh IDs for fresh results.
Nutrition aggregation uses Health Connect's source rules rather than manually
summing potentially duplicated records. Food goals are user choices, not medical
recommendations. Accounts stay pinned and tokens are never exported to the agent.

Disable sharing/control or disconnect in Health Connect settings at any time.
Disconnect revokes app permissions but retains food receipts for safe retries.
Previously exported Drive versions, agent copies and logged meals are not erased.
Private cache/receipts are in Android no-backup storage. Do not put private health
exports or credentials in this public repository.

## Build and validation

Use the Android SDK/JDK setup in [DASHBOARD.md](DASHBOARD.md#build-and-tests). Build debug and tests:
`./gradlew :app:app:testDefaultDebugUnitTest :app:app:assembleDefaultDebug :app:app:assembleDefaultDebugAndroidTest`.
Install both APKs, then run instrumentation with
`adb shell am instrument -w -e class de.mm20.launcher2.capture.HealthConnectTest,de.mm20.launcher2.capture.HealthDriveSyncTest,de.mm20.launcher2.capture.HealthWidgetTest io.github.audacitymicro.dashboard.debug.test/androidx.test.runner.AndroidJUnitRunner`.
Use a test phone/emulator with synthetic data; never export real health data in logs.

Official references: [setup](https://developer.android.com/health-and-fitness/health-connect/get-started),
[data types](https://developer.android.com/health-and-fitness/health-connect/data-types),
[aggregation](https://developer.android.com/health-and-fitness/health-connect/aggregate-data),
[SDK releases](https://developer.android.com/jetpack/androidx/releases/health-connect).
