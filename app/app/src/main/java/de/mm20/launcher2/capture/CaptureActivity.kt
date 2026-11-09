package de.mm20.launcher2.capture

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.auth.api.identity.Identity
import java.io.File
import java.util.concurrent.Executors

/** A separate launcher entry and home-screen shortcut preserve upstream search and launch behavior. */
open class CaptureActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var editor: EditText
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var recordButton: Button
    private var savingText = false
    private var recorder: MediaRecorder? = null
    private var recording: File? = null
    private var player: MediaPlayer? = null
    private val refresh = object : Runnable {
        override fun run() { load(); main.postDelayed(this, 3000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24) }
        val scroll = ScrollView(this).apply { addView(layout) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
        }
        ViewCompat.requestApplyInsets(scroll)
        layout.addView(TextView(this).apply { text = "Dashboard Inbox"; textSize = 26f })
        status = TextView(this); layout.addView(status)
        editor = EditText(this).apply {
            hint = "Drop a thought here…"; minLines = 3; maxLines = 8
            setText(savedInstanceState?.getString("draft", ""))
        }; layout.addView(editor)
        fun button(label: String, action: () -> Unit): Button = Button(this).also {
            it.text = label; it.setOnClickListener { action() }; layout.addView(it)
        }
        button("Save text offline") {
            if (savingText) return@button
            val text = editor.text.toString()
            if (text.isBlank()) { message("Write a note first"); return@button }
            savingText = true
            background({ InboxStore(this).use { it.save("text", "text/plain", text) } }) {
                savingText = false
                if (editor.text.toString() == text) editor.setText("")
                saved()
            }
        }
        recordButton = button("Record voice note") {
            if (recorder != null) stopRecording()
            else requestRecording()
        }
        button("Add image") { pick("image/*") }
        button("Import audio") { pick("audio/*") }
        button("Connect Google Drive / retry sign-in") { authorize() }
        button("Pause Drive sync") {
            background({ DriveAuth.set(this, false) }) { message("Sync paused; local captures are kept"); load() }
        }
        button("Retry queued or failed uploads") {
            background({ InboxStore(this).use { it.retry() }; SyncQueue.enqueue(this) }) { load() }
        }
        button("Check for Dashboard updates") {
            startActivity(Intent(this, de.mm20.launcher2.updates.UpdateActivity::class.java))
        }
        button("Copy agent setup prompt") {
            background({ de.mm20.launcher2.dashboard.DashboardStore(this).use { it.snapshot() } }) { board ->
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                    android.content.ClipData.newPlainText("Dashboard agent setup", de.mm20.launcher2.dashboard.AgentSetup.prompt(board)))
                message("Agent setup prompt copied")
            }
        }
        button("Copy agent file IDs") {
            background({ de.mm20.launcher2.dashboard.DashboardStore(this).use { it.snapshot() } }) { board ->
                if (board.instructionsFileId.isBlank()) message("Connect Drive and finish syncing first")
                else {
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                        android.content.ClipData.newPlainText("Dashboard agent files",
                            "Instructions: ${board.instructionsFileId}\nAgent board: ${board.agentFileId}\nPhone task state (read only): ${board.phoneFileId}"))
                    message("Agent file IDs copied")
                }
            }
        }
        val showSetup = Switch(this).apply { text = "Show setup on home screen"; isEnabled = false }
        layout.addView(showSetup)
        var resettingSetup = false
        background({ de.mm20.launcher2.dashboard.DashboardStore(this).use { !it.snapshot().setupHidden } }) { show ->
            showSetup.isChecked = show; showSetup.isEnabled = true
            showSetup.setOnCheckedChangeListener { _, checked ->
                if (resettingSetup) return@setOnCheckedChangeListener
                showSetup.isEnabled = false
                background({
                    try {
                        de.mm20.launcher2.dashboard.DashboardStore(this).use { it.setMeta("setupHidden", (!checked).toString()) }
                        true
                    } catch (_: Exception) { false }
                }) { saved ->
                    showSetup.isEnabled = true
                    if (saved) message(if (checked) "Setup will appear on Home" else "Setup hidden from Home")
                    else {
                        resettingSetup = true; showSetup.isChecked = !checked; resettingSetup = false
                        message("Could not save the home-screen preference. Try again.")
                    }
                }
            }
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; layout.addView(rows)
        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND) {
            val mime = intent.type.orEmpty()
            if (mime == "text/plain") editor.setText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
            else {
                @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) importUri(uri)
            }
        }
        if (savedInstanceState == null) main.post {
            if (!isFinishing && !isDestroyed) when (initialCaptureMode()) {
                "text" -> {
                    editor.requestFocus()
                    getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                "voice" -> requestRecording()
                "image" -> pick("image/*")
            }
        }
    }
    // Only the non-exported internal entry point can request an automatic capture action.
    protected open fun initialCaptureMode(): String? = null
    private fun requestRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 20)
        else startRecording()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("draft", editor.text.toString()); super.onSaveInstanceState(outState) }
    override fun onStart() { super.onStart(); main.post(refresh) }
    override fun onStop() {
        main.removeCallbacks(refresh)
        if (recorder != null) stopRecording()
        player?.release(); player = null
        super.onStop()
    }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 20 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
        else if (requestCode == 20) message("Microphone permission is needed for recording")
    }
    private fun pick(type: String) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE), 10)
    }
    @Deprecated("Platform callback used without an ActivityX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { if (requestCode == 30) message("Drive sign-in cancelled; notes stay offline"); return }
        if (requestCode == 10) data?.data?.let { importUri(it) }
        if (requestCode == 30 && data != null) {
            try { connected(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data).accessToken) }
            catch (_: Exception) { message("Drive authorization failed; try connecting again") }
        }
    }
    private fun importUri(uri: Uri) {
        background({
            require(uri.scheme == "content") { "Choose a document from the system picker" }
            val mime = contentResolver.getType(uri).orEmpty()
            require(mime.matches(Regex("(image|audio)/[A-Za-z0-9.+-]+"))) { "Unsupported image or audio type" }
            val stream = requireNotNull(contentResolver.openInputStream(uri)) { "Cannot open capture" }
            InboxStore(this).use { it.save(if (mime.startsWith("image/")) "image" else "voice", mime, source = stream) }
        }) { saved() }
    }
    @Suppress("DEPRECATION") private fun startRecording() {
        try {
            val file = File(noBackupFilesDir, "recording-${java.util.UUID.randomUUID()}.m4a")
            val active = MediaRecorder(); recorder = active; recording = file
            active.setAudioSource(MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            active.setOutputFile(file.path); active.setMaxFileSize(25L * 1024 * 1024); active.setMaxDuration(600000)
            active.setOnInfoListener { _, _, _ -> if (recorder != null) stopRecording() }
            active.prepare(); active.start(); recordButton.text = "Stop and save voice note"
        } catch (_: Exception) {
            recorder?.release(); recorder = null; recording?.delete(); recording = null
            message("Could not start microphone")
        }
    }
    private fun stopRecording() {
        val active = recorder ?: return
        val file = recording ?: return
        recorder = null; recording = null; recordButton.text = "Record voice note"
        try {
            active.stop()
            background({
                InboxStore(this).use { it.save("voice", "audio/mp4", source = file.inputStream()) }
                file.delete() // Retain the completed recording if inbox commit fails.
            }) { saved() }
        } catch (_: Exception) { file.delete(); message("Recording was too short to save") }
        finally { active.release() }
    }
    private fun authorize() {
        Identity.getAuthorizationClient(this).authorize(DriveAuth.request())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    try { startIntentSenderForResult(requireNotNull(result.pendingIntent).intentSender, 30, null, 0, 0, 0) }
                    catch (_: Exception) { message("Could not open Google authorization") }
                } else connected(result.accessToken)
            }.addOnFailureListener { message("Google authorization unavailable. Check Play Services and OAuth setup.") }
    }
    private fun connected(token: String?) {
        if (token == null) { message("No Drive token returned"); return }
        background({
            val subject = DriveAuth.subject(token)
            val previous = DriveAuth.state(this).optString("subject")
            require(SyncPolicy.sameOwner(previous, subject)) { "Use the previously connected Google account to protect queued notes" }
            DriveAuth.set(this, true, subject)
            InboxStore(this).use { it.retry() }; SyncQueue.enqueue(this)
        }) { message("Google Drive connected"); load() }
    }
    private fun saved() { message("Saved offline"); load() }
    private fun load() {
        background({ InboxStore(this).use { it.list().take(100) } to DriveAuth.state(this).optBoolean("enabled") }) { (captures, enabled) ->
            status.text = if (enabled) "Drive connected • uploads when online" else "Offline capture ready • Drive paused"
            rows.removeAllViews()
            if (captures.isEmpty()) rows.addView(TextView(this).apply { text = "Your saved captures appear here." })
            captures.forEach { capture ->
                rows.addView(Button(this).apply {
                    text = "${capture.kind} • ${capture.state}\n${capture.text.take(100).ifEmpty { java.time.Instant.ofEpochMilli(capture.created).toString() }}"
                    setOnClickListener { preview(capture) }
                })
            }
        }
    }
    private fun preview(capture: Capture) {
        if (capture.kind == "text") AlertDialog.Builder(this).setMessage(capture.text).setPositiveButton("Close", null).show()
        else if (capture.kind == "voice") {
            try {
                player?.release(); player = MediaPlayer().apply {
                    setDataSource(InboxStore(this@CaptureActivity).use { it.file(capture.id).path })
                    prepare(); start(); setOnCompletionListener { it.release(); player = null }
                }
                message("Playing voice note")
            } catch (_: Exception) { player?.release(); player = null; message("Could not play audio") }
        } else background({
            val path = InboxStore(this).use { it.file(capture.id).path }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            options.inSampleSize = 1
            while (options.outWidth / options.inSampleSize > 1200 || options.outHeight / options.inSampleSize > 1200) options.inSampleSize *= 2
            options.inJustDecodeBounds = false
            requireNotNull(BitmapFactory.decodeFile(path, options)) { "Image preview unavailable" }
        }) { bitmap ->
            AlertDialog.Builder(this).setView(ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true })
                .setPositiveButton("Close", null).show()
        }
    }
    private fun <T> background(work: () -> T, done: (T) -> Unit) {
        if (isDestroyed || io.isShutdown) return
        io.execute {
            try { val result = work(); main.post { if (!isDestroyed) done(result) } }
            catch (e: Exception) { main.post { if (!isDestroyed) { savingText = false; message("Capture action failed. Check permissions, storage and account setup.") } } }
        }
    }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}
