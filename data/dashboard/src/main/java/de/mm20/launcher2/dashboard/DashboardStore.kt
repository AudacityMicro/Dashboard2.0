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
            require(todos.none { it.id.startsWith("local-") }) { "local- IDs are reserved for phone tasks" }
            return AgentBoard(messages, todos)
        }
    }
}
data class DashboardSnapshot(val updates: List<AgentUpdate>, val tasks: List<BoardTask>, val status: String, val lastSync: Long, val agentFileId: String = "", val phoneFileId: String = "", val instructionsFileId: String = "")

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
        AgentBoard.parse(raw) // Validate fully before replacing the last-known-good copy.
        setMeta("board", raw)
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
        }
        return id
    }
    fun complete(task: BoardTask, completed: Boolean) = transaction {
        if (task.local) writableDatabase.update("local_tasks", ContentValues().apply { put("completed", if (completed) 1 else 0) }, "id=?", arrayOf(task.id))
        else writableDatabase.insertWithOnConflict("completions", null, ContentValues().apply {
            put("id", task.id); put("completed", if (completed) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        bumpRevision()
    }
    private fun bumpRevision() = setMeta("revision", ((meta("revision").toLongOrNull() ?: 0) + 1).toString())
    fun snapshot(): DashboardSnapshot {
        val remote = meta("board").let { if (it.isEmpty()) AgentBoard(emptyList(), emptyList()) else AgentBoard.parse(it) }
        val checks = readableDatabase.rawQuery("SELECT id,completed FROM completions", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1) == 1) }
        }
        val local = readableDatabase.rawQuery("SELECT id,title,completed FROM local_tasks ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(BoardTask(c.getString(0), c.getString(1), c.getInt(2) == 1, "", true)) }
        }
        return DashboardSnapshot(remote.updates, (remote.tasks.map { it.copy(completed = checks[it.id] ?: it.completed) } + local)
            .sortedBy { it.completed }, meta("status"), meta("lastSync").toLongOrNull() ?: 0, meta("agentFile"), meta("phoneFile"),
            if (meta("instructionsReady") == "true") meta("instructionsFile") else "")
    }
    fun phoneState(): String = JSONObject().apply {
        put("schemaVersion", 1); put("revision", meta("revision").toLongOrNull() ?: 0)
        put("updatedAt", java.time.Instant.now().toString())
        put("tasks", JSONArray().apply { snapshot().tasks.forEach { t -> put(JSONObject().apply {
            put("id", t.id); put("title", t.title); put("completed", t.completed); put("due", t.due)
            put("source", if (t.local) "phone" else "agent")
        }) } })
    }.toString(2)
    companion object {
        const val EMPTY_BOARD = "{\"schemaVersion\":1,\"updates\":[],\"tasks\":[]}"
    }
}
