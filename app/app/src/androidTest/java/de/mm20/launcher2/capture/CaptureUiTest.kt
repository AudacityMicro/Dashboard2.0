package de.mm20.launcher2.capture

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureUiTest {
    @Test fun saveFromInboxUiAndPreserveHomeEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName)
        val resolved = context.packageManager.resolveActivity(home, 0)
        assertEquals("de.mm20.launcher2.ui.launcher.LauncherActivity", resolved?.activityInfo?.name)
        val text = "Offline UI test " + java.util.UUID.randomUUID()
        var id: String? = null
        try {
            ActivityScenario.launch(CaptureActivity::class.java).use {
                onView(withHint("Drop a thought here…")).perform(replaceText(text), closeSoftKeyboard())
                onView(withText("Save text offline")).perform(scrollTo(), click())
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (System.nanoTime() < deadline && id == null) {
                    id = InboxStore(context).use { store -> store.list().find { c -> c.text == text }?.id }
                    if (id == null) Thread.sleep(40)
                }
                assertNotNull("UI save did not reach durable inbox", id)
                InboxStore(context).use { store -> assertEquals(text, store.file(requireNotNull(id)).readText()) }
            }
        } finally {
            id?.let { saved -> InboxStore(context).use { store ->
                store.writableDatabase.delete("captures", "id=?", arrayOf(saved)); store.file(saved).delete()
            } }
        }
    }
}
