package de.mm20.launcher2.ui.launcher.widgets.health

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.mm20.launcher2.health.*
import de.mm20.launcher2.ui.dashboard.reportGridContentHeight
import kotlinx.coroutines.*
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun HealthCard() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var snapshot by remember { mutableStateOf(HealthSnapshot()) }
    var goals by remember { mutableStateOf(HealthGoals()) }
    var enabled by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    suspend fun cached() {
        val state = withContext(Dispatchers.IO) { HealthStore(context).use { store ->
            listOf(store.snapshot(), store.goals(), store.flag("enabled"), store.meta("status"))
        } }
        snapshot = state[0] as HealthSnapshot; goals = state[1] as HealthGoals
        enabled = state[2] as Boolean; error = state[3] as String
    }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            cached()
            while (isActive) {
                withContext(Dispatchers.IO) { HealthRepository.refresh(context) }
                cached()
                if (enabled) context.sendBroadcast(Intent("${context.packageName}.dashboard.SYNC")
                    .setPackage(context.packageName).putExtra("resume", true))
                delay(30_000)
            }
        }
    }
    fun settings() { context.startActivity(Intent().setClassName(context.packageName, "de.mm20.launcher2.health.HealthConnectActivity")) }
    HealthCardContent(snapshot, goals, enabled, error, ::settings)
}

@Composable
fun HealthCardContent(snapshot: HealthSnapshot, goals: HealthGoals, enabled: Boolean, error: String = "", settings: () -> Unit = {}) {
    val today = snapshot.date == LocalDate.now().toString()
    fun kcal(value: Double?) = value?.takeIf { today }?.roundToInt()?.toString() ?: "—"
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).reportGridContentHeight().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!enabled) {
            Text("Your daily calories", style = MaterialTheme.typography.titleMedium)
            Text("Connect Health Connect to see food and activity here.")
            TextButton(onClick = settings) { Text("Connect Health Connect") }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("Eaten" to kcal(snapshot.consumed), "Remaining" to kcal(snapshot.remaining(goals)),
                    "Burned" to kcal(snapshot.burned)).forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                        Text("$label · kcal", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(if (goals.foodKcal == null) "Ask your agent to set your food goal."
                else "Goal ${goals.foodKcal!!.roundToInt()} kcal" + if (goals.creditActive) " · active credit ${kcal(snapshot.active)} kcal" else "",
                style = MaterialTheme.typography.bodySmall)
            if (goals.creditActive && today && snapshot.active == null) Text("Active calories unavailable; no activity credit added.", style = MaterialTheme.typography.bodySmall)
            if (snapshot.status.isNotBlank()) Text(snapshot.status, style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall)
            if (snapshot.updated > 0) Text("Updated ${((System.currentTimeMillis() - snapshot.updated) / 60_000).coerceAtLeast(0)} min ago",
                style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = settings, contentPadding = PaddingValues(0.dp)) { Text("Health settings") }
        }
    }
}
