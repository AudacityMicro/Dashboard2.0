package de.mm20.launcher2.capture

import androidx.test.core.app.ActivityScenario
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import de.mm20.launcher2.ui.theme.transparency.LocalTransparencyScheme
import de.mm20.launcher2.ui.theme.transparency.TransparencyScheme
import de.mm20.launcher2.ui.dashboard.DashboardPanel
import de.mm20.launcher2.ui.base.ProvideCompositionLocals
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import de.mm20.launcher2.dashboard.DashboardStore
import de.mm20.launcher2.dashboard.AgentSetup
import de.mm20.launcher2.ui.launcher.LauncherActivity
import org.junit.Assert.*
import org.junit.Rule
import kotlinx.coroutines.flow.first
import org.junit.Test

class DashboardHomeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun settingsUpdateButtonStartsAutomaticGitHubFlow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val observed = java.util.concurrent.atomic.AtomicReference<android.content.Intent>()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.component?.className != de.mm20.launcher2.updates.UpdateActivity::class.java.name) return null
                observed.set(intent)
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(de.mm20.launcher2.ui.settings.SettingsActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithText("Update launcher from GitHub").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Update launcher from GitHub").performClick()
                compose.waitUntil(5000) { observed.get() != null }
                assertTrue(observed.get().getBooleanExtra("automatic", false))
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun refreshMovesToMenuAndRequestsDashboardSync() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val received = java.util.concurrent.atomic.AtomicInteger(0)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) { received.incrementAndGet() }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver,
            android.content.IntentFilter("${context.packageName}.dashboard.SYNC"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("search-overflow-menu").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Refresh").assertDoesNotExist()
                compose.onNodeWithText("Capture inbox").assertDoesNotExist()
                received.set(0)
                compose.onNodeWithTag("search-overflow-menu").performClick()
                compose.onNodeWithTag("capture-inbox-menu").assertExists()
                compose.onNodeWithTag("refresh-dashboard-menu").performClick()
                compose.waitUntil(5000) { received.get() > 0 }
            }
        } finally { context.unregisterReceiver(receiver) }
    }
    @Test fun dashboardCardsFollowTransparencyAndDockStaysBelowScrollingContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val oldHidden = DashboardStore(context).use { it.meta("setupHidden") }
        val oldBoard = DashboardStore(context).use { it.meta("board") }
        val opacity = androidx.compose.runtime.mutableFloatStateOf(0f)
        try {
            DashboardStore(context).use {
                it.setMeta("setupHidden", "false")
                it.acceptBoard("""{"schemaVersion":1,"updates":[{"id":"grid-test","title":"A useful note","body":"Prepare for today"}],"tasks":[{"id":"grid-task","title":"A task","completed":false}]}""")
            }
            ActivityScenario.launch(LauncherActivity::class.java).use { home ->
                home.onActivity { activity -> activity.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalTransparencyScheme provides TransparencyScheme(surface = opacity.floatValue)) {
                            ProvideCompositionLocals { Box(Modifier.fillMaxSize().background(Color.Blue)) { DashboardPanel() } }
                        }
                    }
                } }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("agent-setup").fetchSemanticsNodes().isNotEmpty() }
                fun topPixel(tag: String): Color {
                    compose.onNodeWithTag(tag).performScrollTo()
                    val image = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
                    return image[image.width / 2, 2]
                }
                for (tag in listOf("agent-setup", "agent-updates", "todo-list")) assertEquals(Color.Blue, topPixel(tag))
                val dock = compose.onNodeWithTag("capture-actions")
                val dockBounds = dock.getUnclippedBoundsInRoot()
                val image = dock.captureToImage().toPixelMap()
                assertEquals(Color.Blue, image[image.width / 2, 2])
                compose.onNodeWithTag("agent-setup").performScrollTo()
                assertEquals(dockBounds, dock.getUnclippedBoundsInRoot())
                compose.runOnIdle { opacity.floatValue = 1f }
                assertNotEquals(Color.Blue, topPixel("agent-updates"))
                screenshot("dashboard-transparent-dock.png")
            }
        } finally { DashboardStore(context).use { it.setMeta("setupHidden", oldHidden); it.setMeta("board", oldBoard) } }
    }
    @Test fun searchOverflowOpensLauncherUpdater() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(de.mm20.launcher2.updates.UpdateActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("search-overflow-menu").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("search-overflow-menu").performClick()
                compose.onNodeWithText("Update launcher").performClick()
                val opened = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                assertNotNull(opened)
                instrumentation.runOnMainSync { opened.finish() }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun voiceStartsOnPointerDownAndSavesOnceOnRelease() = assertHeldCapture(false)
    @Test fun cancelledVoiceGestureStillSavesExactlyOnce() = assertHeldCapture(true)
    private fun assertHeldCapture(cancelGesture: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        val before = InboxStore(context).use { it.list().map { capture -> capture.id }.toSet() }
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("capture-voice").fetchSemanticsNodes().isNotEmpty() }
                val button = compose.onNodeWithTag("capture-voice")
                button.performTouchInput { down(center) }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("capture-hold-recording").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("inline-capture-sheet").assertDoesNotExist()
                Thread.sleep(1600) // MediaRecorder needs actual elapsed time, not the Compose test clock.
                assertEquals(before, InboxStore(context).use { store -> store.list().map { capture -> capture.id }.toSet() })
                screenshot("dashboard-hold-recording.png")
                button.performTouchInput { if (cancelGesture) cancel() else up() }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("inline-capture-saved").fetchSemanticsNodes().isNotEmpty() }
                InboxStore(context).use { store ->
                    val added = store.list().filter { it.id !in before }
                    assertEquals(1, added.size); assertTrue(store.file(added.single().id).length() > 0)
                }
            }
        } finally { InboxStore(context).use { store ->
            store.list().filter { it.id !in before }.forEach { store.writableDatabase.delete("captures", "id=?", arrayOf(it.id)); store.file(it.id).delete() }
        } }
    }
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    @Test fun photoButtonLaunchesCameraAutomaticallyAndCancelKeepsInboxUnchanged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val observed = java.util.concurrent.atomic.AtomicReference<android.content.Intent>()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != android.provider.MediaStore.ACTION_IMAGE_CAPTURE) return null
                observed.set(intent)
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        val before = InboxStore(context).use { it.list().map { capture -> capture.id }.toSet() }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("capture-image").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("capture-image").performClick()
                compose.waitUntil(5000) { observed.get() != null }
                val uri = observed.get().getParcelableExtra<android.net.Uri>(android.provider.MediaStore.EXTRA_OUTPUT)
                assertEquals("content", uri?.scheme)
                assertEquals("${context.packageName}.fileprovider", uri?.authority)
                compose.waitForIdle()
                assertEquals(before, InboxStore(context).use { store -> store.list().map { capture -> capture.id }.toSet() })
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun cameraResultCopiesFullResolutionPhotoIntoInbox() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != android.provider.MediaStore.ACTION_IMAGE_CAPTURE) return null
                val uri = requireNotNull(intent.getParcelableExtra<android.net.Uri>(android.provider.MediaStore.EXTRA_OUTPUT))
                val image = android.graphics.Bitmap.createBitmap(1600, 1200, android.graphics.Bitmap.Config.ARGB_8888)
                image.eraseColor(android.graphics.Color.BLUE)
                context.contentResolver.openOutputStream(uri)!!.use { image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                image.recycle()
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, null)
            }
        }
        val before = InboxStore(context).use { it.list().map { capture -> capture.id }.toSet() }
        var id: String? = null
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("capture-image").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("capture-image").performClick()
                compose.waitUntil(10000) { id = InboxStore(context).use { store -> store.list().find { it.id !in before }?.id }; id != null }
                InboxStore(context).use { store ->
                    val capture = store.list().single { capture -> capture.id == id }
                    assertEquals("image/jpeg", capture.mime)
                    val image = android.graphics.BitmapFactory.decodeFile(store.file(requireNotNull(id)).path)
                    assertEquals(1600, image.width); assertEquals(1200, image.height); image.recycle()
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            id?.let { saved -> InboxStore(context).use { store -> store.writableDatabase.delete("captures", "id=?", arrayOf(saved)); store.file(saved).delete() } }
        }
    }
    @Test fun uploadButtonOpensDocumentsAutomaticallyAndCopiesFileOffline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = java.io.File(context.cacheDir, "capture-test.pdf").apply { writeText("%PDF-1.4 offline document") }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val observed = java.util.concurrent.atomic.AtomicReference<android.content.Intent>()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != android.content.Intent.ACTION_OPEN_DOCUMENT) return null
                observed.set(intent)
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, android.content.Intent().setData(uri))
            }
        }
        var id: String? = null
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("capture-file").fetchSemanticsNodes().isNotEmpty() }
                screenshot("capture-home.png")
                compose.onNodeWithTag("capture-file").performClick()
                compose.waitUntil(5000) { observed.get() != null }
                assertEquals("*/*", observed.get().type)
                compose.waitUntil(10000) {
                    id = InboxStore(context).use { store -> store.list().find { it.name == file.name }?.id }; id != null
                }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("inline-capture-saved").fetchSemanticsNodes().isNotEmpty() }
                file.delete()
                InboxStore(context).use { store ->
                    assertEquals("%PDF-1.4 offline document", store.file(requireNotNull(id)).readText())
                    assertEquals("file", store.list().single { capture -> capture.id == id }.kind)
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor); file.delete()
            id?.let { saved -> InboxStore(context).use { store -> store.writableDatabase.delete("captures", "id=?", arrayOf(saved)); store.file(saved).delete() } }
        }
    }
    @Test fun hiddenSetupSurvivesRecreationAndCanBeRestoredFromInbox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val old = DashboardStore(context).use { it.meta("setupHidden") }
        try {
            DashboardStore(context).use { it.setMeta("setupHidden", "false") }
            ActivityScenario.launch(LauncherActivity::class.java).use { home ->
                compose.waitUntil(10000) { compose.onAllNodesWithTag("agent-setup").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("agent-setup").performScrollTo()
                compose.onNodeWithTag("hide-dashboard-setup").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
                compose.waitUntil(5000) {
                    DashboardStore(context).use { it.snapshot().setupHidden } &&
                        compose.onAllNodesWithTag("agent-setup").fetchSemanticsNodes().isEmpty()
                }
                home.recreate()
                compose.waitForIdle()
                compose.onNodeWithTag("agent-setup").assertDoesNotExist()
                compose.onNodeWithText("Copy agent file IDs").assertDoesNotExist()
                compose.onNodeWithTag("capture-actions").assertExists()
                compose.onNodeWithTag("todo-list").assertDoesNotExist()
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val monitor = instrumentation.addMonitor(CaptureActivity::class.java.name, null, false)
                try {
                    compose.onNodeWithTag("search-overflow-menu").performClick()
                    compose.onNodeWithTag("capture-inbox-menu").performClick()
                    val inbox = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5000))
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Copy agent setup prompt"))
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isEnabled()))
                    val show = androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Show setup on home screen"))
                    var setupSwitch: android.widget.Switch? = null
                    show.check { view, failure -> if (failure != null) throw failure; setupSwitch = view as android.widget.Switch }
                    compose.waitUntil(5000) {
                        var ready = false
                        instrumentation.runOnMainSync { ready = setupSwitch?.isEnabled == true }
                        ready
                    }
                    show.check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isNotChecked()))
                        .perform(androidx.test.espresso.action.ViewActions.scrollTo())
                    instrumentation.runOnMainSync { requireNotNull(setupSwitch).performClick() }
                    compose.waitUntil(5000) { DashboardStore(context).use { !it.snapshot().setupHidden } }
                    instrumentation.runOnMainSync { inbox.finish() }
                } finally { instrumentation.removeMonitor(monitor) }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("agent-setup").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            }
        } finally { DashboardStore(context).use { it.setMeta("setupHidden", old) } }
    }
    @Test fun updateScreenOpensFromHomeWithoutStartingANetworkCheck() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(de.mm20.launcher2.updates.UpdateActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("check-dashboard-updates").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("agent-setup").performScrollTo()
                compose.onNodeWithTag("check-dashboard-updates").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
                val opened = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                assertNotNull(opened)
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Tap Check now to contact GitHub."))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                instrumentation.runOnMainSync { opened.finish() }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun setupPromptIsAvailableOnHomeBeforeDriveConnection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(LauncherActivity::class.java).use {
            compose.waitUntil(10000) { compose.onAllNodesWithTag("agent-setup").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("copy-agent-setup").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("agent-setup-copied").assertExists()
            compose.runOnIdle {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                val prompt = requireNotNull(clip).getItemAt(0).text.toString()
                assertTrue(prompt.contains(AgentSetup.INSTRUCTIONS_NAME))
                assertTrue(prompt.contains("BEFORE EACH Dashboard"))
                assertTrue(prompt.contains("Never request my Google password"))
            }
        }
    }
    @Test fun mainHomeOmitsCreationControlsAndStillChecksOffATask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = "Home task " + java.util.UUID.randomUUID()
        val id = DashboardStore(context).use { it.addTask(title) }
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithTag("capture-text").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("agent-updates").assertDoesNotExist()
                compose.onNodeWithTag("add-task-dock").assertDoesNotExist()
                compose.onNodeWithTag("search-overflow-menu").performClick()
                compose.onNodeWithTag("add-task-menu").assertDoesNotExist()
                compose.onNodeWithText("Add task").assertDoesNotExist()
                androidx.test.espresso.Espresso.pressBack()
                compose.onNodeWithTag("add-task-widget").assertDoesNotExist()
                compose.onNodeWithTag("capture-hold-hint").assertDoesNotExist()
                compose.onNodeWithText("Hold Voice to record").assertDoesNotExist()
                compose.onNodeWithTag("todo-list").performScrollTo()
                compose.onNodeWithTag("task-check-${id}").performScrollTo().performClick()
                compose.waitUntil(5000) { DashboardStore(context).use { store -> store.snapshot().tasks.find { task -> task.id == id }?.completed == true } }
                DashboardStore(context).use { store -> assertTrue(store.snapshot().tasks.single { task -> task.id == id }.completed) }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("toggle-completed").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("task-check-${id}").assertDoesNotExist()
                compose.onNodeWithTag("toggle-completed").performScrollTo().performClick()
                compose.onNodeWithTag("task-check-${id}").assertExists()
                compose.onNodeWithTag("toggle-completed").performClick()
                compose.onNodeWithTag("task-check-${id}").assertDoesNotExist()
            }
        } finally {
            DashboardStore(context).use { store -> store.writableDatabase.delete("local_tasks", "id=?", arrayOf(id)) }
        }
    }
    @Test fun homeCaptureSavesTextInlineAndPreservesDraftAcrossRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(QuickCaptureActivity::class.java.name, null, false)
        val context = instrumentation.targetContext
        val ui = org.koin.core.context.GlobalContext.get().get<de.mm20.launcher2.preferences.ui.UiSettings>()
        val orientation = kotlinx.coroutines.runBlocking { ui.orientation.first() }
        ui.setOrientation(de.mm20.launcher2.preferences.ScreenOrientation.Portrait)
        kotlinx.coroutines.runBlocking { ui.orientation.first { it == de.mm20.launcher2.preferences.ScreenOrientation.Portrait } }
        val text = "Inline note ${java.util.UUID.randomUUID()}"
        val title = "A note title"
        val savedText = "$title\n\n$text"
        var id: String? = null
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use { home ->
                compose.waitUntil(10000) { compose.onAllNodesWithTag("capture-actions").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
                compose.onNodeWithTag("capture-text").assertExists()
                compose.onNodeWithTag("capture-voice").assertExists()
                compose.onNodeWithTag("capture-image").assertExists()
                compose.onNodeWithTag("capture-file").assertExists()
                compose.onNodeWithTag("capture-text").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("inline-note-editor").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
                compose.onNodeWithTag("inline-note-save").assertIsNotEnabled()
                compose.onNodeWithTag("inline-note-title").performTextInput(title)
                compose.onNodeWithTag("inline-note-save").assertIsEnabled()
                compose.onNodeWithTag("inline-note-editor").performTextInput(text)
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                compose.waitForIdle()
                screenshot("inline-text.png")
                home.recreate()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("inline-note-editor").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
                compose.onNodeWithTag("inline-note-editor").assertTextContains(text)
                compose.onNodeWithTag("inline-note-title").assertTextContains(title)
                compose.onNodeWithTag("inline-note-close").performClick()
                compose.onNodeWithText("Discard unsaved note?").assertExists()
                compose.onNodeWithText("Keep editing").performClick()
                compose.onNodeWithTag("inline-note-save").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
                compose.waitUntil(5000) {
                    id = InboxStore(context).use { it.list().find { capture -> capture.text == savedText }?.id }; id != null
                }
                // One Save returns to Home after the durable write, with no Done/OK step.
                compose.waitUntil(5000) { compose.onAllNodesWithTag("inline-note-editor").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
                compose.onNodeWithTag("inline-capture-saved").assertDoesNotExist()
                InboxStore(context).use {
                    assertEquals(savedText, it.file(requireNotNull(id)).readText())
                    assertEquals(1, it.list().count { capture -> capture.text == savedText })
                }
                home.recreate()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("capture-text").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
                compose.onNodeWithTag("capture-text").performClick()
                compose.onNodeWithTag("inline-note-title").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                compose.onNodeWithTag("inline-note-editor").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                compose.onNodeWithTag("inline-note-save").assertIsNotEnabled()
                compose.onNodeWithTag("inline-note-title").performTextInput("Unsaved title only")
                compose.onNodeWithTag("inline-note-close").performClick()
                compose.onNodeWithText("Discard unsaved note?").assertExists()
                compose.onNodeWithText("Discard", substring = false).performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("inline-note-editor").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
                compose.onNodeWithTag("capture-text").performClick()
                compose.onNodeWithTag("inline-note-title").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                compose.onNodeWithTag("inline-note-save").assertIsNotEnabled()
                assertTrue(InboxStore(context).use { it.list().none { capture -> capture.text.contains("Unsaved title only") } })
                assertEquals("Inline capture should not open another Activity", 0, monitor.hits)
            }
        } finally {
            ui.setOrientation(orientation)
            instrumentation.removeMonitor(monitor)
            id?.let { saved -> InboxStore(context).use { store ->
                store.writableDatabase.delete("captures", "id=?", arrayOf(saved)); store.file(saved).delete()
            } }
        }
    }

    @Test fun voiceRecordsInsideHomeAndSavesWhenLauncherLeavesForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        val before = InboxStore(context).use { it.list().map { capture -> capture.id }.toSet() }
        val added = mutableListOf<String>()
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use { home ->
                compose.waitUntil(10000) { compose.onAllNodesWithTag("capture-voice").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("capture-voice").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("inline-voice-record").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("inline-voice-record").assertExists()
                compose.waitUntil(5000) { compose.onAllNodesWithText("00:02").fetchSemanticsNodes().isNotEmpty() }
                screenshot("inline-voice.png")
                home.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                compose.waitUntil(5000) {
                    InboxStore(context).use { store ->
                        added.clear(); added.addAll(store.list().filter { it.id !in before }.map { it.id }); added.size == 1
                    }
                }
                home.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                compose.waitUntil(5000) { compose.onAllNodesWithTag("inline-capture-saved").fetchSemanticsNodes().isNotEmpty() }
                InboxStore(context).use { store ->
                    val capture = store.list().single { it.id in added }
                    assertEquals("voice", capture.kind); assertTrue(store.file(capture.id).length() > 0)
                }
                compose.waitUntil(60000) { InboxStore(context).use { store -> store.list().single { it.id in added }.transcription != "pending" } }
                InboxStore(context).use { store ->
                    assertTrue(store.list().single { it.id in added }.transcription in listOf("ready", "no_speech"))
                }
                compose.onNodeWithTag("inline-voice-transcript").assertExists()
            }
        } finally { InboxStore(context).use { store ->
            store.list().filter { it.id !in before }.forEach { store.writableDatabase.delete("captures", "id=?", arrayOf(it.id)); store.file(it.id).delete() }
        } }
    }

}
