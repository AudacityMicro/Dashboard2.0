package de.mm20.launcher2.capture

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.dashboard.*
import de.mm20.launcher2.ui.base.ProvideCompositionLocals
import de.mm20.launcher2.ui.component.markdown.MarkdownText
import de.mm20.launcher2.ui.dashboard.DashboardPanel
import de.mm20.launcher2.ui.overlays.OverlayHost
import de.mm20.launcher2.ui.settings.SettingsActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DashboardMarkdownTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun dashboardRendersMarkdownOfflineAndRealTasksRemainEditable() {
        val keys = listOf("board", "setupHidden") + GridProfile.entries.map { "grid.v1.home.${it.name}" }
        val before = DashboardStore(context).use { db -> keys.associateWith { db.meta(it) } }
        val id = UUID.randomUUID().toString()
        val body = "## Morning plan\n\n**Bring ID** and *leave early*.\n\n- Check the route\n- Pack lunch\n\n1. First stop\n2. Second stop\n\n> Rain after lunch\n\nUse `Platform 2`.\n\n[Journey details](https://example.com/journey)\n\n- [ ] Read-only reminder"
        val raw = JSONObject().put("schemaVersion", 1).put("updates", JSONArray().put(JSONObject()
            .put("id", id).put("title", "Today's focus").put("body", body)))
            .put("tasks", JSONArray().put(JSONObject().put("id", id).put("title", "Actual actionable task").put("completed", false))).toString()
        try {
            DashboardStore(context).use { it.acceptBoard(raw); it.setMeta("setupHidden", "true") }
            GridProfile.entries.forEach { profile -> WidgetGridStore(context).save("home", profile,
                WidgetGridLayout(4).place("updates", GridCell(0, 0, 4, 12))
                    .place("tasks", GridCell(0, 12, 4, 5)).copy(hidden = setOf("setup", "connection"))) }
            ActivityScenario.launch(SettingsActivity::class.java).use { home ->
                home.onActivity { activity -> activity.setContent { MaterialTheme { ProvideCompositionLocals {
                    CompositionLocalProvider(LocalConfiguration provides Configuration(context.resources.configuration).apply {
                        screenWidthDp = 400; screenHeightDp = 900
                    }) { OverlayHost(Modifier.fillMaxSize()) { DashboardPanel(includeNativeWidgets = false) } }
                } } } }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("agent-markdown-$id").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Morning plan", substring = true).assertExists()
                val paragraph = compose.onNodeWithText("Bring ID and leave early.").fetchSemanticsNode()
                    .config[SemanticsProperties.Text].single()
                assertTrue(paragraph.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
                assertTrue(paragraph.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
                assertFalse(paragraph.text.contains("**"))
                compose.onNodeWithText("Check the route").assertExists()
                compose.onNodeWithText("Second stop").assertExists()
                compose.onNodeWithText("Rain after lunch").assertExists()
                compose.onNodeWithText("Use Platform 2.").assertExists()
                compose.onNodeWithText("Journey details").assertExists()
                compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("agent-markdown-$id"))).assertHasNoClickAction()
                if (InstrumentationRegistry.getArguments().getString("screenshots") == "true") {
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
                    java.io.File(context.getExternalFilesDir(null), "dashboard-markdown.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                compose.onNodeWithTag("task-check-$id").performScrollTo().performClick()
                compose.waitUntil(10000) { DashboardStore(context).use { it.snapshot().tasks.single().completed } }
                assertEquals(body, DashboardStore(context).use { it.snapshot().updates.single().body })
                home.recreate()
                // Source formatting survives store reopen independently of the rendering Activity.
                assertEquals(raw, DashboardStore(context).use { it.meta("board") })
            }
        } finally { DashboardStore(context).use { db ->
            before.forEach { (key, value) -> db.setMeta(key, value) }
            db.writableDatabase.delete("completions", "id=?", arrayOf(id))
        } }
    }

    @Test fun linksRefreshWithContentAndReadOnlyMarkdownOpensOnlyWebDestinations() {
        val observed = java.util.concurrent.CopyOnWriteArrayList<String>()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                observed.add(intent.dataString.orEmpty())
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        val content = mutableStateOf("[Open details](https://example.com/first)")
        fun clickLink() {
            val node = compose.onNodeWithText("Open details")
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val point = layouts.single().getBoundingBox(3).center
            node.performTouchInput { click(point) }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { home ->
                home.onActivity { it.setContent { MaterialTheme { MarkdownText(content.value) } } }
                clickLink()
                assertEquals(listOf("https://example.com/first"), observed.toList())
                compose.runOnIdle { content.value = "[Open details](https://example.com/second)" }
                clickLink()
                assertEquals("https://example.com/second", observed.last())
                for (destination in listOf("javascript:alert(1)", "file:///private", "intent://anything")) {
                    compose.runOnIdle { content.value = "[Open details]($destination)" }
                    clickLink()
                    assertEquals(2, observed.size)
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun editableMarkdownStillCallsItsExistingCheckboxCallback() {
        val content = mutableStateOf("- [ ] Existing note checkbox")
        ActivityScenario.launch(SettingsActivity::class.java).use { home ->
            home.onActivity { it.setContent { MaterialTheme {
                MarkdownText(content.value, onTextChange = { updated -> content.value = updated })
            } } }
            compose.onNode(isToggleable()).performClick()
            compose.waitUntil(5000) { content.value.contains("[x]") }
            compose.onNode(isToggleable()).assertIsOn()
        }
    }

    @Test fun setupPromptTeachesMarkdownWithValidJsonAndPreservesInstructionFileId() {
        val prompt = AgentSetup.prompt(DashboardSnapshot(emptyList(), emptyList(), "", 0,
            agentFileId = "board-file", phoneFileId = "phone-file", instructionsFileId = "guide-file"))
        assertTrue(prompt.contains("Guide version: 8"))
        assertTrue(prompt.contains("Markdown as needed"))
        assertTrue(prompt.contains("file ID guide-file"))
        assertTrue(prompt.contains("Keep update titles, task titles and due values plain text"))
        val example = AgentSetup.instructions.lineSequence().first { it.trim().startsWith("{\"schemaVersion\"") }
        val board = AgentBoard.parse(example)
        assertTrue(board.updates.single().body.contains("**Priority:**"))
        assertTrue(board.updates.single().body.contains("\n- Bring your notes"))
        assertTrue(prompt.contains("Do not replace the guide with a new file"))
    }
}
