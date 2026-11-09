package de.mm20.launcher2.ui.dashboard

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import de.mm20.launcher2.ui.component.LauncherCard
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
    scrollingEnabled: Boolean = true, footer: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val capture: InlineCaptureViewModel = viewModel(factory = viewModelFactory {
        initializer { InlineCaptureViewModel(app as Application, createSavedStateHandle()) }
    })
    var board by remember { mutableStateOf(DashboardSnapshot(emptyList(), emptyList(), "", 0)) }
    var error by remember { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
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
    Column(modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Column(Modifier.weight(1f).verticalScroll(scroll, enabled = scrollingEnabled).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loaded && !board.setupHidden) LauncherCard(Modifier.fillMaxWidth().testTag("agent-setup")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Set up your agent", style = MaterialTheme.typography.titleLarge)
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
            LauncherCard(Modifier.fillMaxWidth().testTag("agent-updates")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Agent updates", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    }
                    if (board.updates.isEmpty()) Text(if (board.setupHidden) "No agent updates yet." else
                        "Your agent’s information will appear here. Your last saved updates remain available offline.")
                    board.updates.forEach { update ->
                        Text(update.title, style = MaterialTheme.typography.titleMedium)
                        if (update.body.isNotEmpty()) Text(update.body, style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider()
                    }
                    if (board.connectionIssue.isNotEmpty()) Text(board.connectionIssue,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("drive-disconnected"))

                }
            }
            LauncherCard(Modifier.fillMaxWidth().testTag("todo-list")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("To-do list", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
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
                    if (board.tasks.isEmpty()) Text("Add a task here or let your agent send one.")
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
            footer()
        }
        CaptureDock(capture, Modifier.fillMaxWidth().padding(vertical = 8.dp), onAddTask = { adding = true })
    }
    InlineCaptureSheet(capture)
    if (adding) AlertDialog(
        onDismissRequest = { adding = false }, title = { Text("New task") },
        text = { Column {
            OutlinedTextField(value = draft, onValueChange = { if (it.length <= 300) draft = it },
                label = { Text("Task") }, modifier = Modifier.testTag("new-task-title"), singleLine = true)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = draft.isNotBlank() && !saving, onClick = {
            val title = draft
            saving = true
            // Close only after the durable write succeeds; retain draft on a failure.
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { DashboardStore(app).use { it.addTask(title) } }
                    draft = ""; adding = false; reload(); sync(); error = ""
                } catch (_: Exception) { error = "Could not save task. Check available storage." }
                finally { saving = false }
            }
        }) { Text("Save task") } },
        dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } }
    )
}
