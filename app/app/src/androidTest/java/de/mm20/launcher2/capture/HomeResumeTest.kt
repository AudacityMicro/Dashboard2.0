package de.mm20.launcher2.capture

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.GestureNavContract
import de.mm20.launcher2.dashboard.*
import de.mm20.launcher2.ui.dashboard.*
import de.mm20.launcher2.ui.launcher.scaffold.ScaffoldPage
import de.mm20.launcher2.ui.launcher.transitions.*
import de.mm20.launcher2.ui.settings.SettingsActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HomeResumeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun returningHomeKeepsCachedWidgetsVisibleWhileDiskRefreshIsBlocked() {
        val parent = "resume-${UUID.randomUUID()}"
        val gate = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>(null)
        val wrapped = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): java.io.File {
                if (Looper.myLooper() != Looper.getMainLooper()) gate.get()?.let { (started, release) ->
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Test layout read was not released" }
                }
                return super.getNoBackupFilesDir()
            }
        }
        val store = WidgetGridStore(context)
        val original = WidgetGridLayout(4).place("cached", GridCell(0, 0, 2, 3))
        try {
            store.save(parent, GridProfile.Portrait, original)
            ActivityScenario.launch(SettingsActivity::class.java).use { screen ->
                screen.onActivity { it.setContent { MaterialTheme {
                    CompositionLocalProvider(LocalContext provides wrapped,
                        LocalConfiguration provides Configuration(context.resources.configuration).apply {
                            screenWidthDp = 400; screenHeightDp = 900
                        }) {
                        ProfiledWidgetGrid(parent, listOf(GridTile("cached", "Cached") { modifier -> Text("Cached content", modifier) }))
                    }
                } } }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("grid-item-cached").fetchSemanticsNodes().isNotEmpty() }
                repeat(3) { cycle ->
                    val old = compose.onNodeWithTag("grid-item-cached").getUnclippedBoundsInRoot()
                    screen.moveToState(Lifecycle.State.CREATED)
                    val width = if (cycle % 2 == 0) 3 else 2
                    store.save(parent, GridProfile.Portrait, original.place("cached", GridCell(0, 0, width, 3)))
                    val started = CountDownLatch(1); val release = CountDownLatch(1)
                    gate.set(started to release)
                    try {
                        screen.moveToState(Lifecycle.State.RESUMED)
                        compose.waitUntil(5000) { started.count == 0L }
                        // With the previous loaded=false code this node disappeared until IO finished.
                        compose.onNodeWithTag("grid-item-cached").assertIsDisplayed()
                        assertEquals(old, compose.onNodeWithTag("grid-item-cached").getUnclippedBoundsInRoot())
                    } finally { gate.set(null); release.countDown() }
                    compose.waitUntil(5000) {
                        compose.onNodeWithTag("grid-item-cached").getUnclippedBoundsInRoot().let { it.right - it.left } != old.right - old.left
                    }
                }
            }
        } finally {
            gate.getAndSet(null)?.second?.countDown()
            DashboardStore(context).use { it.writableDatabase.delete("meta", "key LIKE ?", arrayOf("grid.v1.$parent.%")) }
            GridEditing.active = false
        }
    }

    @Test fun unmatchedReturnsSkipTheFadeAndMatchedIconsStillGetTheirTransition() {
        ActivityScenario.launch(SettingsActivity::class.java).use { screen ->
            screen.onActivity { activity ->
                val manager = EnterHomeTransitionManager()
                val callback = Message.obtain().apply { replyTo = Messenger(Handler(Looper.getMainLooper())) }
                val contract = GestureNavContract(ComponentName(context.packageName, "fixture.App"), android.os.Process.myUserHandle(), callback)
                manager.resolve(contract, activity.window, ScaffoldPage.Home)
                assertNull(manager.currentTransition.replayCache.single())
                val bounds = IntRect(12, 24, 60, 72)
                val handler = EnterHomeTransitionHandler { EnterHomeTransitionParams(bounds) }
                manager.registerHandler(handler, ScaffoldPage.Home)
                manager.resolve(contract, activity.window, ScaffoldPage.Home)
                val transition = requireNotNull(manager.currentTransition.replayCache.single())
                assertEquals(bounds, transition.targetBounds)
                assertNotNull(transition.startBounds)
                manager.clear()
                assertNull(manager.currentTransition.replayCache.single())
            }
        }
    }
}
