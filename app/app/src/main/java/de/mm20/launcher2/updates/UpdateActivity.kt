package de.mm20.launcher2.updates

import android.app.Activity
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
    private lateinit var checkButton: Button
    private lateinit var downloadButton: Button
    private var available: DashboardRelease? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        checkButton = Button(this).also { it.text = "Check now"; it.setOnClickListener { check() }; layout.addView(it) }
        downloadButton = Button(this).also {
            it.text = "Download update"; it.visibility = android.view.View.GONE
            it.setOnClickListener { available?.let { release ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.download)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                catch (_: ActivityNotFoundException) { status.text = "No browser found. Install a browser to download the update." }
            } }; layout.addView(it)
        }
        layout.addView(TextView(this).apply {
            text = "Your browser downloads the APK. Open it and approve Android's Update prompt. Install over this app; do not uninstall, so your local notes stay on this phone. Android checks the package signature."
        })
        Button(this).also { it.text = "Back"; it.setOnClickListener { finish() }; layout.addView(it) }
        status.text = "Tap Check now to contact GitHub."
    }
    private fun check() {
        val version = ReleaseVersion.parse(BuildConfig.VERSION_NAME)
        if (packageName != "io.github.audacitymicro.dashboard.debug" || version == null) {
            status.text = "This update channel supports the Dashboard preview APK. Use your build provider for this installation."
            return
        }
        available = null; downloadButton.visibility = android.view.View.GONE
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
                    available = release?.takeIf { it.version > version }
                    downloadButton.visibility = if (available != null) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
    }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
}
