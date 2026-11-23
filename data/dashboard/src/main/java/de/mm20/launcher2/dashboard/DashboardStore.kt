package de.mm20.launcher2.dashboard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class AgentUpdate(val id: String, val title: String, val body: String)
data class BoardTask(val id: String, val title: String, val completed: Boolean, val due: String, val local: Boolean = false)
data class AgentBoard(val updates: List<AgentUpdate>, val tasks: List<BoardTask>) {
    companion object {
        fun parse(raw: String): AgentBoard {
            require(raw.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "Board exceeds 512 KiB" }
            val json = JSONObject(raw)
            require(json.getInt("schemaVersion") == 1) { "Unsupported board version" }
            fun field(item: JSONObject, name: String, max: Int, optional: Boolean = false): String {
                val value = if (optional && !item.has(name)) "" else item.getString(name)
                require(value.length <= max && (optional || value.isNotBlank())) { "Invalid board field: $name" }
                return value
            }
            val updates = json.getJSONArray("updates"); val tasks = json.getJSONArray("tasks")
            require(updates.length() <= 50 && tasks.length() <= 200) { "Too many board entries" }
            val messages = (0 until updates.length()).map { i -> updates.getJSONObject(i).let {
                AgentUpdate(field(it, "id", 100), field(it, "title", 200), field(it, "body", 4000, true))
            } }
            val todos = (0 until tasks.length()).map { i -> tasks.getJSONObject(i).let {
                BoardTask(field(it, "id", 100), field(it, "title", 300), if (it.has("completed")) it.getBoolean("completed") else false,
                    field(it, "due", 100, true))
            } }
            require(messages.map { it.id }.distinct().size == messages.size && todos.map { it.id }.distinct().size == todos.size) { "Duplicate board IDs" }
            return AgentBoard(messages, todos)
        }
    }
}
data class DashboardSnapshot(val updates: List<AgentUpdate>, val tasks: List<BoardTask>, val status: String, val lastSync: Long, val agentFileId: String = "", val phoneFileId: String = "", val instructionsFileId: String = "", val setupHidden: Boolean = false,
    val connectionIssue: String = "")

/** Remote board is agent-owned; phone edits live in a separate durable overlay and export. */
class DashboardStore(context: Context, name: String = "dashboard.db") : SQLiteOpenHelper(context,
    File(context.noBackupFilesDir, name).path, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE local_tasks(id TEXT PRIMARY KEY, title TEXT NOT NULL, completed INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE completions(id TEXT PRIMARY KEY, completed INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun meta(key: String): String = readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else ""
    }
    fun setMeta(key: String, value: String) { writableDatabase.insertWithOnConflict("meta", null,
        ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun acceptBoard(raw: String) {
        val board = AgentBoard.parse(raw)
        val json = JSONObject(raw)
        require(!json.has("acknowledgedTaskEdits") || json.get("acknowledgedTaskEdits") is JSONArray)
        val acks = json.optJSONArray("acknowledgedTaskEdits") ?: JSONArray()
        require(acks.length() <= 2000)
        // Validate every receipt before changing any task or replacing the cached board.
        val receipts = (0 until acks.length()).map { acks.getJSONObject(it).let { ack ->
            val device = ack.getString("deviceId"); val task = ack.getString("taskId"); val revision = ack.getLong("revision")
            require(device.isNotBlank() && device.length <= 100 && task.isNotBlank() && task.length <= 200 && revision > 0 && ack.getDouble("revision") == revision.toDouble())
            Triple(device, task, revision)
        } }
        transaction {
            for ((device, task, revision) in receipts) {
                val edit = meta("taskEdit-$task").takeIf { it.isNotBlank() }?.let { JSONObject(it) }
                if (device == meta("deviceId") && edit?.getLong("revision") == revision && board.tasks.any { it.id == task }) {
                    writableDatabase.delete("completions", "id=?", arrayOf(task))
                    writableDatabase.delete("local_tasks", "id=?", arrayOf(task))
                    writableDatabase.delete("meta", "key=?", arrayOf("taskEdit-$task"))
                }
            }
            setMeta("board", raw)
        }
    }
    private inline fun transaction(work: () -> Unit) {
        val db = writableDatabase; db.beginTransaction()
        try { work(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    fun addTask(title: String): String {
        val trimmed = title.trim(); require(trimmed.isNotEmpty() && trimmed.length <= 300)
        val id = "local-${UUID.randomUUID()}"
        transaction {
            writableDatabase.insertOrThrow("local_tasks", null, ContentValues().apply { put("id", id); put("title", trimmed) })
            bumpRevision()
            recordEdit(BoardTask(id, trimmed, false, "", true))
        }
        return id
    }
    fun complete(task: BoardTask, completed: Boolean) = transaction {
        if (task.local) writableDatabase.update("local_tasks", ContentValues().apply { put("completed", if (completed) 1 else 0) }, "id=?", arrayOf(task.id))
        else writableDatabase.insertWithOnConflict("completions", null, ContentValues().apply {
            put("id", task.id); put("completed", if (completed) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        bumpRevision()
        recordEdit(task.copy(completed = completed))
    }
    private fun bumpRevision() = setMeta("revision", ((meta("revision").toLongOrNull() ?: 0) + 1).toString())
    private fun recordEdit(task: BoardTask, legacy: Boolean = false) {
        setMeta("taskEdit-${task.id}", JSONObject().put("id", task.id).put("title", task.title).put("completed", task.completed)
            .put("due", task.due).put("source", if (task.local) "phone" else "agent").put("revision", meta("revision").toLong())
            .put("changedAt", if (legacy) JSONObject.NULL else java.time.Instant.now().toString()).put("legacy", legacy).toString())
    }
    private fun initializeDeviceEdits() = transaction {
        if (meta("deviceId").isBlank()) setMeta("deviceId", UUID.randomUUID().toString())
        if (meta("taskEditsInitialized") != "true") {
            val overrides = readableDatabase.rawQuery("SELECT id FROM completions", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            for (task in snapshot().tasks.filter { it.local || it.id in overrides }) if (meta("taskEdit-${task.id}").isBlank()) {
                bumpRevision(); recordEdit(task, legacy = true)
            }
            setMeta("taskEditsInitialized", "true")
        }
    }
    fun snapshot(): DashboardSnapshot {
        val remote = meta("board").let { if (it.isEmpty()) AgentBoard(emptyList(), emptyList()) else AgentBoard.parse(it) }
        val checks = readableDatabase.rawQuery("SELECT id,completed FROM completions", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1) == 1) }
        }
        val local = readableDatabase.rawQuery("SELECT id,title,completed FROM local_tasks ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(BoardTask(c.getString(0), c.getString(1), c.getInt(2) == 1, "", true)) }
        }
        return DashboardSnapshot(remote.updates, (remote.tasks.filter { t -> local.none { it.id == t.id } }.map { it.copy(completed = checks[it.id] ?: it.completed) } + local)
            .sortedBy { it.completed }, meta("status"), meta("lastSync").toLongOrNull() ?: 0, meta("agentFile"), meta("phoneFile"),
            if (meta("instructionsReady") == "true") meta("instructionsFile") else "", meta("setupHidden") == "true",
            when (meta("connection")) {
                "connected" -> ""
                "reconnect" -> "Google Drive disconnected. Reconnect through the menu’s Capture inbox."
                "paused" -> "Google Drive is disconnected or paused. Connect through the menu’s Capture inbox."
                else -> if (meta("agentFile").isBlank()) "Connect Google Drive through the menu’s Capture inbox." else ""
            })
    }
    fun phoneState(): String {
        initializeDeviceEdits()
        return JSONObject().apply {
        put("schemaVersion", 1); put("revision", meta("revision").toLongOrNull() ?: 0)
        put("deviceId", meta("deviceId")); put("platform", "android")
        put("taskEdits", JSONArray().apply {
            readableDatabase.rawQuery("SELECT value FROM meta WHERE key LIKE 'taskEdit-%'", null).use { c -> while (c.moveToNext()) put(JSONObject(c.getString(0))) }
        })
        put("updatedAt", java.time.Instant.now().toString())
        put("tasks", JSONArray().apply { snapshot().tasks.forEach { t -> put(JSONObject().apply {
            put("id", t.id); put("title", t.title); put("completed", t.completed); put("due", t.due)
            put("source", if (t.local) "phone" else "agent")
        }) } })
        if (meta("healthAgentFile").isNotBlank() && meta("healthPhoneFile").isNotBlank()) {
            put("health", JSONObject().put("agentFileId", meta("healthAgentFile")).put("phoneFileId", meta("healthPhoneFile")))
        }
        }.toString(2)
    }
    companion object {
        const val EMPTY_BOARD = "{\"schemaVersion\":1,\"updates\":[],\"tasks\":[]}"
    }
}
