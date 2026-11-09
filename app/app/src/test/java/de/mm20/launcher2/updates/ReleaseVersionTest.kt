package de.mm20.launcher2.updates

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    private fun version(value: String) = requireNotNull(ReleaseVersion.parse(value))
    @Test fun previewNumbersCompareNumerically() {
        assertTrue(version("v0.1.0-preview.10") > version("0.1.0-preview.2"))
        assertEquals(version("v0.1.0-preview.3"), version("0.1.0-preview.3"))
    }
    @Test fun stableSupersedesPreviewButNotNewerBaseVersion() {
        assertTrue(version("0.1.0") > version("0.1.0-preview.999"))
        assertTrue(version("0.2.0-preview.1") > version("0.1.0"))
        assertTrue(version("1.0.0") > version("0.99.99"))
    }
    @Test fun equalAndOlderVersionsDoNotTriggerAnUpdate() {
        assertFalse(version("0.1.0-preview.3") > version("0.1.0-preview.3"))
        assertFalse(version("0.1.0-preview.2") > version("0.1.0-preview.3"))
    }
    @Test fun unsupportedOrOverflowingTagsAreRejected() {
        listOf("", "latest", "0.1", "v0.1.0-preview.3/other", "0.1.0-beta.1", "01.0.0",
            "0.1.0-preview.-1", "0.1.0-preview.9999999999999999999999").forEach { assertNull(ReleaseVersion.parse(it)) }
    }
}
