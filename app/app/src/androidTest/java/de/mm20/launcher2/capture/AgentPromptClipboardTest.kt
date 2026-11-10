package de.mm20.launcher2.capture

import android.content.ClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.dashboard.*
import de.mm20.launcher2.ui.dashboard.GridEditing
import de.mm20.launcher2.ui.launcher.LauncherActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AgentPromptClipboardTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun homeAndInboxCopyTheCompleteSameCompactPrompt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val keys = listOf("agentFile", "phoneFile", "instructionsFile", "instructionsReady", "setupHidden", "board", "connection") +
            GridProfile.entries.map { "grid.v1.home.${it.name}" }
        val before = DashboardStore(context).use { db -> keys.associateWith { db.meta(it) } }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        var oldClip: android.content.ClipData? = null
        instrumentation.runOnMainSync { oldClip = clipboard.primaryClip }
        fun copied(): String {
            var value = ""
            instrumentation.runOnMainSync { value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty() }
            return value
        }
        try {
            GridEditing.active = false
            DashboardStore(context).use {
                it.setMeta("agentFile", "agent-clipboard-fixture")
                it.setMeta("phoneFile", "phone-clipboard-fixture")
                it.setMeta("instructionsFile", "guide-clipboard-fixture")
                it.setMeta("instructionsReady", "true"); it.setMeta("setupHidden", "false")
                it.setMeta("connection", "connected"); it.setMeta("board", DashboardStore.EMPTY_BOARD)
            }
            GridProfile.entries.forEach { profile -> WidgetGridStore(context).save("home", profile,
                WidgetGridLayout(profile.columns).place("setup", GridCell(0, 0, profile.columns, 8))) }
            val expected = DashboardStore(context).use { AgentSetup.prompt(it.snapshot()) }
            ActivityScenario.launch(LauncherActivity::class.java).use {
                compose.waitUntil(15000) { compose.onAllNodesWithTag("copy-agent-setup").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
                compose.onNodeWithTag("copy-agent-setup").performScrollTo().performClick()
                assertEquals(expected, copied())
                assertTrue(copied().length < 1600)
                assertTrue(copied().endsWith("Report blockers in chat."))
            }
            ActivityScenario.launch(CaptureActivity::class.java).use {
                instrumentation.runOnMainSync { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", "before copy")) }
                onView(withText("Copy agent setup prompt")).perform(scrollTo(), click())
                compose.waitUntil(10000) { copied() == expected }
                assertEquals(expected, copied())
            }
        } finally {
            DashboardStore(context).use { db -> before.forEach { (key, value) -> db.setMeta(key, value) } }
            GridEditing.active = false
            instrumentation.runOnMainSync { oldClip?.let { clipboard.setPrimaryClip(it) } ?: clipboard.clearPrimaryClip() }
        }
    }
}
