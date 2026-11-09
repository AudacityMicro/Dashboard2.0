package de.mm20.launcher2.updates

import android.app.Activity
import android.app.DownloadManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import de.mm20.launcher2.BuildConfig
import java.util.concurrent.Executors

/** Checks only on request. Downloads and package installation remain user-controlled. */
class UpdateActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var patchNotes: TextView
    private lateinit var checkButton: Button
    private lateinit var downloadButton: Button
    private var available: DashboardRelease? = null
    private lateinit var downloads: UpdateDownload
    private val main = Handler(Looper.getMainLooper())
    private var verifying = false
    private var awaitingPermission = false
    private var active = false
    private val poll = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || downloads.id < 0 || verifying) return
            when (runCatching { downloads.status() }.getOrDefault(DownloadManager.STATUS_FAILED)) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    verifying = true; status.text = "Verifying update…"
                    io.execute {
                        val verified = runCatching { downloads.verifyCompleted() }.isSuccess
                        runOnUiThread {
                            if (!isDestroyed && !isFinishing) {
                                verifying = false
                                if (verified) { showInstall(); if (active) install() }
                                else { downloads.clear(); status.text = "Update verification failed. Nothing was installed. Check again to retry."; checkButton.isEnabled = true }
                            }
                        }
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    downloads.clear(); status.text = "Download failed. Check your connection/storage and try again."
                    checkButton.isEnabled = true
                }
                else -> { status.text = "Downloading update… Android will continue if you leave this screen."; main.postDelayed(this, 1000) }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloads = UpdateDownload(this)
        awaitingPermission = savedInstanceState?.getBoolean("awaitingPermission") ?: false
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }
        val scroll = ScrollView(this).apply { addView(layout) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(32 + bars.left, 24 + bars.top, 32 + bars.right, 24 + bars.bottom); insets
        }
        setContentView(scroll)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        layout.addView(TextView(this).apply { text = "Dashboard updates"; textSize = 26f })
        layout.addView(TextView(this).apply { text = "Installed: ${BuildConfig.VERSION_NAME}" })
        layout.addView(TextView(this).apply {
            text = "Checks published Dashboard releases on GitHub, including previews for preview builds. No Google sign-in is needed."
        })
        status = TextView(this).also { it.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE; layout.addView(it) }
        patchNotes = TextView(this).also {
            it.tag = "update-patch-notes"; it.textSize = 16f
            it.setPadding(0, 16, 0, 16); it.visibility = android.view.View.GONE
            layout.addView(it)
        }
        checkButton = Button(this).also { it.text = "Check now"; it.setOnClickListener { check() }; layout.addView(it) }
        downloadButton = Button(this).also {
            it.text = "Download update"; it.visibility = android.view.View.GONE
            it.setOnClickListener {
                if (downloads.ready != null) install() else available?.let { release -> download(release) }
            }; layout.addView(it)
        }
        layout.addView(TextView(this).apply {
            text = "The APK downloads from GitHub and is checked for its checksum, app identity, version and signing certificate. Approve Android’s installation prompt to finish. Keep this app installed to preserve your notes."
        })
        Button(this).also { it.text = "Back"; it.setOnClickListener { finish() }; layout.addView(it) }
        status.text = "Tap Check now to contact GitHub."
        val restored = savedInstanceState?.getString("releaseTag")?.let { tag ->
            val version = ReleaseVersion.parse(tag)
            val current = ReleaseVersion.parse(BuildConfig.VERSION_NAME)
            if (version != null && current != null && version > current) DashboardRelease(tag, version,
                "${ReleaseClient.REPOSITORY}/releases/download/$tag/${ReleaseClient.APK}",
                savedInstanceState.getString("releaseHash").orEmpty(), savedInstanceState.getLong("releaseSize"),
                ReleaseNotes.summary(savedInstanceState.getString("releaseSummary").orEmpty())) else null
        }
        if (downloads.ready != null) showInstall()
        else if (downloads.id >= 0) showSummary(downloads.summary)
        else if (restored != null) showAvailableRelease(restored)
        else if (intent.getBooleanExtra("automatic", false) && savedInstanceState == null) check()
    }
    private fun check() {
        if (downloads.ready != null) downloads.clear()
        val version = ReleaseVersion.parse(BuildConfig.VERSION_NAME)
        if (packageName != "io.github.audacitymicro.dashboard.debug" || version == null) {
            status.text = "This update channel supports the Dashboard preview APK. Use your build provider for this installation."
            return
        }
        available = null; downloadButton.visibility = android.view.View.GONE; patchNotes.visibility = android.view.View.GONE
        checkButton.isEnabled = false; status.text = "Checking GitHub…"
        io.execute {
            var release: DashboardRelease? = null
            val message = try {
                release = ReleaseClient.check(version)
                when {
                    release == null -> "No compatible APK release found. Try again later."
                    release!!.version > version -> "Update available: ${release!!.tag}"
                    else -> "You're up to date."
                }
            } catch (e: ReleaseCheckFailure) {
                if (e.status == 403 || e.status == 429) "GitHub's request limit was reached. Try again later."
                else "GitHub is unavailable (${e.status}). Try again later."
            } catch (_: Exception) { "Couldn't check for updates. Check your connection and try again." }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    status.text = message; checkButton.isEnabled = true
                    release?.takeIf { it.version > version }?.let { showAvailableRelease(it) }
                }
            }
        }
    }
    private fun showSummary(summary: String) {
        patchNotes.text = "What's new: ${ReleaseNotes.summary(summary)}"
        patchNotes.visibility = android.view.View.VISIBLE
    }
    internal fun showAvailableRelease(release: DashboardRelease) {
        available = release
        status.text = "Update available: ${release.tag}"
        showSummary(release.summary)
        downloadButton.text = "Download and install update"
        downloadButton.visibility = android.view.View.VISIBLE
    }
    private fun download(release: DashboardRelease) {
        try {
            downloads.start(release); checkButton.isEnabled = false
            downloadButton.visibility = android.view.View.GONE
            main.post(poll)
        } catch (_: Exception) { status.text = "Couldn't start a verified download. Check your connection and try again."; checkButton.isEnabled = true }
    }
    private fun showInstall() {
        val label = downloads.tag.takeIf { it.isNotBlank() }?.let { "Update $it" } ?: "Update"
        status.text = "$label verified and ready to install."
        showSummary(downloads.summary)
        downloadButton.text = "Install update"; downloadButton.visibility = android.view.View.VISIBLE
        checkButton.isEnabled = true
    }
    private fun install() {
        val file = downloads.ready ?: return
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                awaitingPermission = true
                status.text = "Allow Dashboard to install updates, then return here to finish."
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            status.text = "Approve Android’s Update prompt. If cancelled, tap Install update to try again."
        } catch (_: ActivityNotFoundException) { status.text = "Android’s installer is unavailable on this device."
        } catch (_: Exception) { status.text = "Couldn't open the installer. Tap Install update to retry." }
    }
    override fun onResume() {
        super.onResume()
        active = true
        if (::downloads.isInitialized) {
            if (downloads.id >= 0) { checkButton.isEnabled = false; main.post(poll) }
            else if (awaitingPermission && downloads.ready != null) {
                awaitingPermission = false
                if (packageManager.canRequestPackageInstalls()) install()
                else status.text = "Installation permission was not granted. Tap Install update when ready."
            }
        }
    }
    override fun onPause() { active = false; main.removeCallbacks(poll); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaitingPermission", awaitingPermission)
        available?.let { release ->
            outState.putString("releaseTag", release.tag); outState.putString("releaseHash", release.sha256)
            outState.putLong("releaseSize", release.size); outState.putString("releaseSummary", release.summary)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { main.removeCallbacks(poll); io.shutdown(); super.onDestroy() }
}
