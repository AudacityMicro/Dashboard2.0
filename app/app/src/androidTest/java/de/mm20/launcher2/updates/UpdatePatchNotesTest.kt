package de.mm20.launcher2.updates

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import de.mm20.launcher2.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UpdatePatchNotesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun isolated(work: () -> Unit) {
        val prefs = context.getSharedPreferences("launcher-update", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        try { prefs.edit().clear().commit(); work() }
        finally { prefs.edit().clear().apply {
            before.forEach { (key, value) -> when (value) {
                is String -> putString(key, value)
                is Long -> putLong(key, value)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
            } }
        }.commit() }
    }
    private fun notes(activity: UpdateActivity) = activity.window.decorView.findViewWithTag<TextView>("update-patch-notes")
    private fun buttons(activity: UpdateActivity): List<TextView> {
        fun collect(view: View): List<TextView> = when (view) {
            is android.widget.Button -> listOf(view)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
            else -> emptyList()
        }
        return collect(activity.window.decorView)
    }
    @Test fun offeredSummaryIsVisibleBeforeDownloadAndSurvivesRecreation() = isolated {
        val release = DashboardRelease("v0.1.0-preview.999", requireNotNull(ReleaseVersion.parse("0.1.0-preview.999")),
            "${ReleaseClient.REPOSITORY}/releases/download/v0.1.0-preview.999/${ReleaseClient.APK}",
            "a".repeat(64), 1234, "Brief patch notes before installing.")
        ActivityScenario.launch(UpdateActivity::class.java).use { screen ->
            screen.onActivity { activity ->
                activity.showAvailableRelease(release)
                assertEquals("What's new: Brief patch notes before installing.", notes(activity).text.toString())
                assertEquals(View.VISIBLE, notes(activity).visibility)
                assertTrue(buttons(activity).any { it.text == "Download and install update" && it.visibility == View.VISIBLE })
                assertEquals(-1L, UpdateDownload(activity).id)
                assertNull(UpdateDownload(activity).ready)
            }
            if (InstrumentationRegistry.getArguments().getString("screenshots") == "true") {
                instrumentation.waitForIdleSync()
                val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
                File(context.getExternalFilesDir(null), "update-patch-notes.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            screen.recreate()
            screen.onActivity { activity ->
                assertEquals("What's new: Brief patch notes before installing.", notes(activity).text.toString())
                assertTrue(buttons(activity).any { it.text == "Download and install update" && it.visibility == View.VISIBLE })
                assertEquals(-1L, UpdateDownload(activity).id)
            }
        }
    }
    @Test fun readyDownloadRetainsItsSummaryAndAutomaticEntryDoesNotOpenInstaller() = isolated {
        val prefs = context.getSharedPreferences("launcher-update", Context.MODE_PRIVATE)
        val file = File(context.cacheDir, "updates/patch-notes-test.apk").also { it.parentFile!!.mkdirs(); it.writeText("fixture") }
        prefs.edit().putLong("base", BuildConfig.VERSION_CODE.toLong()).putString("ready", file.name)
            .putString("tag", "v0.1.0-preview.999").putString("summary", "Better widget controls.").commit()
        val launches = java.util.concurrent.atomic.AtomicInteger()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action !in listOf(Intent.ACTION_VIEW, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) return null
                launches.incrementAndGet()
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<UpdateActivity>(Intent(context, UpdateActivity::class.java).putExtra("automatic", true)).use { screen ->
                screen.onActivity { activity ->
                    assertEquals("What's new: Better widget controls.", notes(activity).text.toString())
                    assertTrue(buttons(activity).any { it.text == "Install update" && it.visibility == View.VISIBLE })
                }
                screen.recreate()
                screen.onActivity { activity -> assertEquals("What's new: Better widget controls.", notes(activity).text.toString()) }
                assertEquals(0, launches.get())
            }
        } finally { instrumentation.removeMonitor(monitor); file.delete() }
    }
}
