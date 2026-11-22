package de.mm20.launcher2.health

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import de.mm20.launcher2.capture.CaptureActivity
import de.mm20.launcher2.dashboard.AgentSetup
import de.mm20.launcher2.dashboard.DashboardStore
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Permissions and cloud sharing are separate, explicit choices. No health OAuth credentials exist. */
class HealthConnectActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var share: CheckBox
    private lateinit var control: CheckBox
    private var binding = false
    private val permissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        work { HealthRepository.refresh(this@HealthConnectActivity, force = true); sync() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 48) }
        val scroll = ScrollView(this).apply { addView(column) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(scroll)
        fun text(value: String, size: Float = 16f) = TextView(this).also { it.text = value; it.textSize = size; column.addView(it) }
        fun button(label: String, action: () -> Unit) = Button(this).also { it.text = label; it.setOnClickListener { action() }; column.addView(it) }
        text("Health Connect", 26f)
        text("Add a Health widget from the widget picker. It shows today's recorded food calories, remaining calories and total calories burned. Remaining includes active calories only, so resting calories are not counted twice.")
        status = text("Loading health settings…")
        button("Connect / calorie permissions") {
            request { HealthStore(this).use { it.flag("enabled", true) }; HealthCatalog.corePermissions }
        }
        button("Choose additional health stats") { request { device -> HealthCatalog.available(device.client).keys.map { HealthCatalog.permission(it) }.toSet() } }
        button("Allow background health reads") { request { device ->
            require(device.supports(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)) { "Background health reads aren't supported on this phone." }
            setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        } }
        button("Allow older health history") { request { device ->
            require(device.supports(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) { "Older-history access isn't supported on this phone." }
            setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        } }
        share = CheckBox(this).also { box ->
            box.text = "Share permitted health stats with my agent through Google Drive"
            box.setOnCheckedChangeListener { _, checked -> if (!binding) work {
                HealthStore(this@HealthConnectActivity).use { it.flag("share", checked); if (checked) it.flag("enabled", true) else it.clearReads() }
                sync()
            } }
            column.addView(box)
        }
        control = CheckBox(this).also { box ->
            box.text = "Allow my agent to log food and change calorie goals"
            box.setOnCheckedChangeListener { _, checked -> if (!binding) work {
                HealthStore(this@HealthConnectActivity).use { it.flag("control", checked); if (checked) it.flag("enabled", true) }
                sync()
            } }
            column.addView(box)
        }
        text("Sharing sends only data you permit to your connected Drive. Your agent uses its own Drive access. Turning sharing off stops future exports and clears the current shared snapshot on the next sync; previously copied data and Drive history remain. Food logging writes only nutrition records, with stable IDs to prevent duplicate meals. Goals are set by your agent from the target you choose.")
        button("Copy agent setup prompt") { work {
            val prompt = DashboardStore(this@HealthConnectActivity).use { AgentSetup.prompt(it.snapshot()) }
            runOnUiThread { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Dashboard agent setup", prompt)); Toast.makeText(this@HealthConnectActivity, "Copied. Paste into your agent.", Toast.LENGTH_SHORT).show() }
        } }
        button("Connect Google Drive") { startActivity(Intent(this, CaptureActivity::class.java)) }
        button("Refresh health and sync") { work { HealthRepository.refresh(this@HealthConnectActivity, force = true); sync() } }
        button("Open Health Connect settings") { runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }.onFailure { status.text = "Health Connect settings aren't available on this phone." } }
        button("Disconnect Health Connect") { work {
            if (HealthDevice.status(this@HealthConnectActivity) == HealthConnectClient.SDK_AVAILABLE) HealthDevice(this@HealthConnectActivity).client.permissionController.revokeAllPermissions()
            HealthStore(this@HealthConnectActivity).use { store ->
                store.flag("enabled", false); store.flag("share", false); store.flag("control", false)
                store.clearReads(); store.snapshot(HealthSnapshot(status = "Health Connect disconnected."))
            }
            sync()
        } }
        button("Health data privacy") { startActivity(Intent(this, HealthPrivacyActivity::class.java)) }
        button("Back") { finish() }
    }
    private fun sync() { sendBroadcast(Intent("$packageName.dashboard.SYNC").setPackage(packageName)) }
    private fun work(task: suspend () -> Unit) {
        background {
            runCatching { runBlocking { task() } }.onFailure { runOnUiThread { if (!isDestroyed) status.text = "Could not finish. Check Health Connect, permissions and your Drive connection." } }
            load()
        }
    }
    private fun request(values: (HealthDevice) -> Set<String>) {
        background {
            val sdk = HealthDevice.status(this)
            if (sdk != HealthConnectClient.SDK_AVAILABLE) {
                runOnUiThread {
                    if (sdk == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                    }.onFailure { status.text = "Install or update Health Connect from Google Play, then return." }
                    else status.text = "Health Connect is not available on this phone. Other launcher features still work."
                }
                return@background
            }
            runCatching { values(HealthDevice(this)) }.onSuccess { requested -> runOnUiThread { if (!isDestroyed) permissions.launch(requested) } }
                .onFailure { failure -> runOnUiThread { if (!isDestroyed) status.text = failure.message ?: "Health permissions aren't available." } }
        }
    }
    private fun load() {
        background {
            val sdk = HealthDevice.status(this)
            val granted = if (sdk == HealthConnectClient.SDK_AVAILABLE) runCatching { runBlocking { HealthDevice(this@HealthConnectActivity).permissions() } }.getOrDefault(emptySet()) else emptySet()
            HealthStore(this).use { store ->
                val goals = store.goals(); val enabled = store.flag("enabled"); val sharing = store.flag("share"); val controls = store.flag("control")
                val note = store.meta("status")
                val summary = buildString {
                    append(if (sdk == HealthConnectClient.SDK_AVAILABLE) "Health Connect available." else "Install/update Health Connect if supported.")
                    append("\nIntegration: ${if (enabled) "on" else "off"}. Granted health permissions: ${granted.size}.")
                    append("\nFood goal: ${goals.foodKcal?.let { "${it.toInt()} kcal" } ?: "not set; ask your agent"}.")
                    append("\nRemaining = goal − eaten${if (goals.creditActive) " + active calories burned" else ""}.")
                    if (HealthDevice.nutritionWrite !in granted) append("\nNutrition write permission is needed for food logging.")
                    if (note.isNotBlank()) append("\n$note")
                }
                runOnUiThread { if (!isDestroyed) {
                    binding = true; share.isChecked = sharing; control.isChecked = controls; binding = false
                    status.text = summary
                } }
            }
        }
    }
    override fun onResume() { super.onResume(); if (::status.isInitialized) load() }
    private fun background(task: () -> Unit) {
        if (!io.isShutdown) try { io.execute(task) } catch (_: java.util.concurrent.RejectedExecutionException) { /* Activity closed while work was being queued. */ }
    }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
}

class HealthPrivacyActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ScrollView(this).apply { addView(TextView(this@HealthPrivacyActivity).apply {
            setPadding(32, 64, 32, 48); textSize = 17f
            text = "Dashboard health privacy\n\nHealth Connect is optional. You choose each health permission and can revoke access in Health Connect settings. Calorie totals and goals power the Health widget. Health snapshots and receipts are stored privately on this phone, outside Android backup.\n\nSharing with your agent through your connected Google Drive is separately opt-in. When enabled, the agent can request the health/fitness record types you grant, using bounded pages and time ranges. Clinical/FHIR documents and route-consent flows are not included. No Google password, OAuth token or Health Connect authorization credential is given to your agent.\n\nAgent control is another opt-in: it can update your calorie goal and log nutrition records. Other health record types are read-only. Food writes use stable IDs and revisions so retries do not duplicate meals. Missing health data is not treated as a diagnosis.\n\nTurn off sharing or disconnect to stop future access. Previously exported Drive data, versions and agent copies are not automatically deleted.\n\nFull privacy policy: https://audacitymicro.github.io/Dashboard2.0/privacy.html"
        }) })
    }
}
