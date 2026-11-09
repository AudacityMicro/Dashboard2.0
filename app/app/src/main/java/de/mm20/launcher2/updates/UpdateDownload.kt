package de.mm20.launcher2.updates

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Downloads survive rotation/process death; only a verified private copy reaches the installer. */
internal class UpdateDownload(private val context: Context) {
    private val prefs = context.getSharedPreferences("launcher-update", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(DownloadManager::class.java)
    init {
        if (prefs.getLong("base", de.mm20.launcher2.BuildConfig.VERSION_CODE.toLong()) != de.mm20.launcher2.BuildConfig.VERSION_CODE.toLong()) {
            if (prefs.getLong("id", -1) >= 0) manager.remove(prefs.getLong("id", -1))
            File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
            prefs.edit().clear().commit()
        }
    }
    val id get() = prefs.getLong("id", -1)
    val ready: File? get() = prefs.getString("ready", null)?.let { File(context.cacheDir, "updates/$it") }?.takeIf { it.isFile }
    fun start(release: DashboardRelease) {
        require(release.sha256.matches(Regex("[0-9a-f]{64}")) && release.size in 1..MAX_SIZE) { "Release has no valid checksum or size" }
        require(release.download == "${ReleaseClient.REPOSITORY}/releases/download/${release.tag}/${ReleaseClient.APK}")
        if (id >= 0) manager.remove(id)
        ready?.delete()
        val request = DownloadManager.Request(Uri.parse(release.download))
            .setTitle("Dashboard launcher update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val downloadId = manager.enqueue(request)
        if (!prefs.edit().clear().putLong("id", downloadId).putString("sha256", release.sha256)
            .putLong("size", release.size).putLong("base", de.mm20.launcher2.BuildConfig.VERSION_CODE.toLong()).commit()) {
            manager.remove(downloadId); throw java.io.IOException("Could not save download state")
        }
    }
    fun status(): Int = manager.query(DownloadManager.Query().setFilterById(id)).use {
        if (!it.moveToFirst() || it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)) > prefs.getLong("size", MAX_SIZE)) DownloadManager.STATUS_FAILED else it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
    }
    fun verifyCompleted(): File {
        val downloadId = id
        check(status() == DownloadManager.STATUS_SUCCESSFUL)
        val directory = File(context.cacheDir, "updates").also { it.mkdirs() }
        val destination = File(directory, "${java.util.UUID.randomUUID()}.apk")
        try {
            manager.openDownloadedFile(id).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    copyVerified(input, destination, prefs.getString("sha256", "").orEmpty(), prefs.getLong("size", 0))
                }
            }
            validatePackage(destination)
            check(prefs.edit().putString("ready", destination.name).remove("id").commit())
            return destination
        } catch (e: Exception) { destination.delete(); throw e
        } finally { runCatching { manager.remove(downloadId) } }
    }
    @Suppress("DEPRECATION")
    fun validatePackage(file: File) {
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, flags)) { "Invalid APK" }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveCode = if (android.os.Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedCode = if (android.os.Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        fun signers(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val expected = signers(installed)
        verifyIdentity(context.packageName, archive.packageName, installedCode, archiveCode, expected, signers(archive))
    }
    fun clear() {
        if (id >= 0) manager.remove(id)
        ready?.delete(); prefs.edit().clear().commit()
    }
    companion object {
        const val MAX_SIZE = 200L * 1024 * 1024
        fun verifyIdentity(expectedPackage: String, actualPackage: String, installedVersion: Long, archiveVersion: Long,
            expectedSigners: Set<String>, archiveSigners: Set<String>) {
            require(actualPackage == expectedPackage) { "Different app package" }
            require(archiveVersion > installedVersion) { "APK is not a newer version" }
            require(expectedSigners.isNotEmpty() && expectedSigners == archiveSigners) { "APK signing certificate does not match" }
        }
        fun copyVerified(input: InputStream, destination: File, expected: String, expectedSize: Long) {
            require(expected.matches(Regex("[0-9a-f]{64}")) && expectedSize in 1..MAX_SIZE)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                destination.outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= expectedSize && total <= MAX_SIZE) { "Update too large" }
                        digest.update(buffer, 0, count); out.write(buffer, 0, count)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(total == expectedSize && actual == expected) { "Update checksum mismatch" }
            } catch (e: Exception) { destination.delete(); throw e }
        }
    }
}
