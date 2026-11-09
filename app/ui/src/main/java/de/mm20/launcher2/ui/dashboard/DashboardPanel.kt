package de.mm20.launcher2.ui.dashboard

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import de.mm20.launcher2.ui.component.LauncherCard
import de.mm20.launcher2.ui.component.markdown.MarkdownText
import de.mm20.launcher2.ui.launcher.widgets.rememberNativeGridWidgets
import de.mm20.launcher2.preferences.WidgetScreenTarget
import de.mm20.launcher2.preferences.ui.UiSettings
import org.koin.compose.koinInject
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.mm20.launcher2.dashboard.*
import kotlinx.coroutines.*
import android.app.Application
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DashboardPanel(modifier: Modifier = Modifier, scroll: ScrollState = rememberScrollState(),
    scrollingEnabled: Boolean = true, includeNativeWidgets: Boolean = true) {
    val context = LocalContext.current
    val app = context.applicationContext
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val capture: InlineCaptureViewModel = viewModel(factory = viewModelFactory {
        initializer { InlineCaptureViewModel(app as Application, createSavedStateHandle()) }
    })
    var board by remember { mutableStateOf(DashboardSnapshot(emptyList(), emptyList(), "", 0)) }
    var error by remember { mutableStateOf("") }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var copiedSetup by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    suspend fun reload() {
        board = withContext(Dispatchers.IO) { DashboardStore(app).use { it.snapshot() } }
        loaded = true
    }
    fun sync() { context.sendBroadcast(Intent("${context.packageName}.dashboard.SYNC").setPackage(context.packageName)) }
    fun change(work: (DashboardStore) -> Unit) { scope.launch {
        try {
            withContext(Dispatchers.IO) { DashboardStore(app).use(work) }
            reload(); sync(); error = ""
        } catch (_: Exception) { error = "Could not save task. Check available storage." }
    } }
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            sync()
            while (isActive) {
                try { reload() } catch (_: Exception) { error = "Could not load dashboard." }
                delay(5000)
            }
        }
    }
    val editing = GridEditing.target == "home"
    val uiSettings: UiSettings = koinInject()
    var pendingNativeEnable by rememberSaveable { mutableStateOf(false) }
    val native = rememberNativeGridWidgets(WidgetScreenTarget.Default.id.toString(), editing,
        onWidgetAdded = { if (!includeNativeWidgets) pendingNativeEnable = true })
    LaunchedEffect(editing, pendingNativeEnable) {
        if (!editing && pendingNativeEnable) { uiSettings.setHomeScreenWidgets(true); pendingNativeEnable = false }
    }
    val tiles = listOf(
        GridTile("setup", "Setup", rows = 8, visible = loaded && !board.setupHidden, supportsAutoHeight = true) { tileModifier ->
            LauncherCard(tileModifier.testTag("agent-setup")) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).reportGridContentHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (board.instructionsFileId.isBlank())
                        "Connect Google Drive from the menu, then copy this prompt to your agent. It explains how to use your captures, updates and tasks."
                        else "Copy this prompt to your agent. It includes your Drive file IDs and asks your agent to check the instructions file for current guidance.")
                    FilledTonalButton(modifier = Modifier.testTag("copy-agent-setup"), onClick = {
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Dashboard agent setup", AgentSetup.prompt(board)))
                        copiedSetup = true
                    }) { Text("Copy agent setup prompt") }
                    TextButton(modifier = Modifier.testTag("check-dashboard-updates"), onClick = {
                        context.startActivity(Intent().setClassName(context.packageName, "de.mm20.launcher2.updates.UpdateActivity"))
                    }) { Text("Check for updates") }
                    TextButton(modifier = Modifier.testTag("hide-dashboard-setup"), onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { DashboardStore(app).use { it.setMeta("setupHidden", "true") } }
                                reload(); error = ""
                            } catch (_: Exception) { error = "Could not save the home-screen preference." }
                        }
                    }) { Text("Hide setup") }
                    Text("You can show setup again from Dashboard Inbox.", style = MaterialTheme.typography.labelSmall)
                    if (copiedSetup) Text(if (board.instructionsFileId.isBlank())
                        "Copied. After Drive syncs, copy again to include your file IDs."
                        else "Copied. Paste it into your agent's chat to finish setup.",
                        modifier = Modifier.testTag("agent-setup-copied"), style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        GridTile("updates", "Updates", rows = 4, visible = board.updates.isNotEmpty(), supportsAutoHeight = true) { tileModifier ->
            LauncherCard(tileModifier.testTag("agent-updates")) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).reportGridContentHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    board.updates.forEach { update ->
                        Text(update.title, style = MaterialTheme.typography.titleMedium)
                        if (update.body.isNotEmpty()) MarkdownText(update.body,
                            modifier = Modifier.fillMaxWidth().testTag("agent-markdown-${update.id}"))
                        HorizontalDivider()
                    }
                }
            }
        },
        GridTile("tasks", "Tasks", rows = 5, visible = board.tasks.isNotEmpty(), supportsAutoHeight = true) { tileModifier ->
            LauncherCard(tileModifier.testTag("todo-list")) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).reportGridContentHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        val completed = board.tasks.count { it.completed }
                        if (completed > 0) TextButton(onClick = { showCompleted = !showCompleted },
                            modifier = Modifier.testTag("toggle-completed").semantics {
                                contentDescription = if (showCompleted) "Hide completed tasks" else "Show completed tasks"
                            }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("$completed done", style = MaterialTheme.typography.labelSmall,
                                color = if (showCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val open = board.tasks.count { !it.completed }
                    Text("$open remaining", style = MaterialTheme.typography.labelMedium)
                    board.tasks.filter { showCompleted || !it.completed }.forEach { task ->
                        key(task.id) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = task.completed, onCheckedChange = { checked -> change { it.complete(task, checked) } },
                                    modifier = Modifier.testTag("task-check-${task.id}").semantics { contentDescription = task.title })
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(task.title, textDecoration = if (task.completed) TextDecoration.LineThrough else null)
                                    if (task.due.isNotEmpty()) Text(task.due, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        GridTile("connection", "Connection", rows = 2, visible = board.connectionIssue.isNotEmpty(), supportsAutoHeight = true) { tileModifier ->
            LauncherCard(tileModifier.testTag("drive-disconnected")) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).reportGridContentHeight().padding(12.dp)) {
                    Text(board.connectionIssue.ifEmpty { "Drive connected" }, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    ) + native.tiles.map { it.copy(visible = includeNativeWidgets) }
    Column(modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Column(Modifier.weight(1f).verticalScroll(scroll, enabled = scrollingEnabled).padding(bottom = 12.dp)) {
            if (loaded && native.loaded) ProfiledWidgetGrid("home", tiles, editing = editing, onAdd = native.add,
                onPlaced = { if (it.startsWith("widget-") && !includeNativeWidgets) pendingNativeEnable = true })
        }
        CaptureDock(capture, Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
    InlineCaptureSheet(capture)
}
