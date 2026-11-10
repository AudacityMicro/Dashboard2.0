package de.mm20.launcher2.capture

import de.mm20.launcher2.dashboard.AgentSetup
import de.mm20.launcher2.dashboard.DashboardSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AgentSetupPromptTest {
    private fun snapshot(guide: String = "g".repeat(128), agent: String = "a".repeat(128), phone: String = "p".repeat(128)) =
        DashboardSnapshot(emptyList(), emptyList(), "", 0, agent, phone, guide)

    @Test fun connectedHandoffStaysSmallAndKeepsEveryIdAndTheEnding() {
        val prompt = AgentSetup.prompt(snapshot())
        assertTrue("Prompt grew beyond a compact handoff: ${prompt.length}", prompt.length < 1600)
        assertTrue(prompt.toByteArray(Charsets.UTF_8).size < 1800)
        for (id in listOf("g".repeat(128), "a".repeat(128), "p".repeat(128))) assertTrue(prompt.contains("file ID $id"))
        assertTrue(prompt.contains("Phone tasks (read-only)"))
        assertTrue(prompt.contains("BEFORE EACH Dashboard run"))
        assertTrue(prompt.contains("Read in sections if needed"))
        assertTrue(prompt.contains(AgentSetup.STARTER_URL))
        assertTrue(prompt.endsWith("Report blockers in chat."))
        assertFalse(prompt.contains(AgentSetup.instructions))
        assertTrue(prompt.length * 10 < AgentSetup.instructions.length)
    }

    @Test fun incompleteSetupGivesABriefReconnectMessageWithoutPartialIds() {
        for (board in listOf(snapshot(guide = ""), snapshot(agent = ""), snapshot(phone = ""))) {
            val prompt = AgentSetup.prompt(board)
            assertTrue(prompt.length < 300)
            assertTrue(prompt.contains("finish syncing")); assertTrue(prompt.contains("copy the setup prompt again"))
            assertFalse(prompt.contains("file ID" + " "))
            assertFalse(prompt.contains(AgentSetup.instructions))
        }
    }

    @Test fun publicStarterIsTheCompleteDriveGuideWithoutUserSpecificData() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "AGENT_STARTER.md").isFile }
        val publicGuide = File(root, "AGENT_STARTER.md").readText(Charsets.UTF_8).trimEnd()
        assertEquals(AgentSetup.instructions, publicGuide)
        assertTrue(publicGuide.length > 15000)
        assertTrue(AgentSetup.STARTER_URL.endsWith("/v0.1.0-preview.19/AGENT_STARTER.md"))
    }
}
