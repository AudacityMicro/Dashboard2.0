package de.mm20.launcher2.updates

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdateDownloadTest {
    @Test fun newerPackagesMustStillMatchAppIdentityAndSigningCertificate() {
        UpdateDownload.verifyIdentity("dashboard", "dashboard", 10, 11, setOf("certificate"), setOf("certificate"))
        val invalid = listOf(
            { UpdateDownload.verifyIdentity("dashboard", "foreign", 10, 11, setOf("certificate"), setOf("certificate")) },
            { UpdateDownload.verifyIdentity("dashboard", "dashboard", 10, 10, setOf("certificate"), setOf("certificate")) },
            { UpdateDownload.verifyIdentity("dashboard", "dashboard", 10, 9, setOf("certificate"), setOf("certificate")) },
            { UpdateDownload.verifyIdentity("dashboard", "dashboard", 10, 11, setOf("certificate"), setOf("attacker")) },
            { UpdateDownload.verifyIdentity("dashboard", "dashboard", 10, 11, emptySet(), emptySet()) })
        invalid.forEach { operation ->
            try { operation(); fail("Unsafe APK accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun verifiedCopyRejectsCorruptionTruncationOversizeAndBrokenStreams() {
        val bytes = "signed update contents".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File.createTempFile("update-test", ".apk")
        try {
            UpdateDownload.copyVerified(bytes.inputStream(), file, hash, bytes.size.toLong())
            assertArrayEquals(bytes, file.readBytes())
            for ((content, expectedSize) in listOf("corrupt".toByteArray() to bytes.size.toLong(),
                bytes.copyOf(bytes.size - 1) to bytes.size.toLong(), bytes to (bytes.size - 1).toLong())) {
                try { UpdateDownload.copyVerified(content.inputStream(), file, hash, expectedSize); fail("Invalid download accepted") }
                catch (_: IllegalArgumentException) { }
                assertFalse(file.exists())
            }
            try {
                UpdateDownload.copyVerified(object : java.io.InputStream() {
                    override fun read(): Int = throw java.io.IOException("Interrupted")
                }, file, hash, bytes.size.toLong())
                fail("Broken stream accepted")
            } catch (_: java.io.IOException) { }
            assertFalse(file.exists())
        } finally { file.delete() }
    }
}
