package de.mm20.launcher2.updates

import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesTest {
    @Test fun explicitPublisherSummaryWinsOverLongReleaseBody() {
        assertEquals("Clearer updates and better links.", ReleaseNotes.summary(
            "# Preview 18\n\nLong background.\n\nWhat's new: **Clearer updates** and [better links](https://example.com).\n\nValidation: many tests"))
    }
    @Test fun legacyParagraphBecomesOnePlainLine() {
        assertEquals("Hold Voice to record, then release to save.", ReleaseNotes.summary(
            "# Preview 15\n\nHold **Voice** to record,\nthen release to save.\n\nInstallation details"))
    }
    @Test fun summariesAreBoundedAndDoNotSplitUnicodePairs() {
        val summary = ReleaseNotes.summary("What's new: " + "a".repeat(158) + "😀" + "b".repeat(30))
        assertTrue(summary.length <= ReleaseNotes.MAX_LENGTH)
        assertTrue(summary.endsWith("…")); assertFalse(summary.contains('\uD83D'))
        assertEquals("Fix one thing.", ReleaseNotes.summary("SUMMARY: Fix\tone\u0000thing."))
    }
    @Test fun absentNotesAreReportedWithoutInventingChanges() {
        for (body in listOf("", "  \n", "<!-- internal -->", "```\nnot release prose\n```"))
            assertEquals(ReleaseNotes.UNAVAILABLE, ReleaseNotes.summary(body))
    }
}
