package de.mm20.launcher2.capture

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.ui.settings.SettingsActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VoiceRecordingSettingsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun settingsMenuOffersAllModesAndPersistsSelectionAcrossRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = VoiceRecordingSettings.get(context)
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { screen ->
                compose.onNodeWithText("Voice recordings").performClick()
                for (mode in listOf(VoiceRecordingMode.Transcript, VoiceRecordingMode.Audio, VoiceRecordingMode.Both)) {
                    compose.onNodeWithText(mode.title).performClick()
                    compose.waitUntil(5000) { VoiceRecordingSettings.get(context) == mode }
                    compose.onNodeWithText(mode.description).assertIsDisplayed()
                }
                screen.recreate()
                compose.waitUntil(10000) {
                    compose.onAllNodesWithText("Audio and transcript").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
                }
                assertEquals(VoiceRecordingMode.Both, VoiceRecordingSettings.get(context))
            }
        } finally { VoiceRecordingSettings.set(context, original) }
    }
}
