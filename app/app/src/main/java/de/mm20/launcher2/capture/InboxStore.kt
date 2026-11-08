package de.mm20.launcher2.capture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class Capture(val id: String, val kind: String, val mime: String, val created: Long,
    val text: String, val state: String, val remote: String, val manifest: String, val owner: String)

/** Both database and payloads are excluded from Android/cloud backup. No external URIs survive import. */
class InboxStore(private val context: Context) : SQLiteOpenHelper(context,
    File(context.noBackupFilesDir, "capture/inbox.db").also { it.parentFile!!.mkdirs() }.path, null, 1) {
    private val root = File(context.noBackupFilesDir, "capture").also { it.mkdirs() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE captures(id TEXT PRIMARY KEY, kind TEXT NOT NULL,
            mime TEXT NOT NULL, created INTEGER NOT NULL, text TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'pending', remote TEXT NOT NULL DEFAULT '',
            manifest TEXT NOT NULL DEFAULT '', owner TEXT NOT NULL DEFAULT '')""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun file(id: String) = File(root, "$id.payload")
    fun save(kind: String, mime: String, text: String = "", source: java.io.InputStream? = null): String {
        require(kind in listOf("text", "voice", "image"))
        require(kind != "text" || text.isNotBlank())
        val id = UUID.randomUUID().toString()
        val temp = File(root, "$id.tmp")
        try {
            FileOutputStream(temp).use { out ->
                if (source == null) out.write(text.toByteArray(Charsets.UTF_8))
                else source.use { input ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) {
                        val size = input.read(buffer); if (size < 0) break
                        total += size; require(total <= 25L * 1024 * 1024) { "Capture exceeds 25 MiB" }
                        out.write(buffer, 0, size)
                    }
                }
                out.fd.sync()
            }
            check(temp.length() > 0) { "Empty capture" }
            check(temp.renameTo(file(id))) { "Cannot commit capture" }
            writableDatabase.insertOrThrow("captures", null, ContentValues().apply {
                put("id", id); put("kind", kind); put("mime", mime)
                put("created", System.currentTimeMillis()); put("text", text)
            })
        } catch (e: Exception) { temp.delete(); file(id).delete(); throw e }
        // Scheduling failure never rolls back a committed local capture; periodic recovery repairs it.
        runCatching { SyncQueue.enqueue(context.applicationContext) }
        return id
    }
    fun list(): List<Capture> = readableDatabase.rawQuery("SELECT * FROM captures ORDER BY created DESC", null).use { c ->
        buildList { while (c.moveToNext()) {
            fun s(name: String) = c.getString(c.getColumnIndexOrThrow(name))
            add(Capture(s("id"), s("kind"), s("mime"), c.getLong(c.getColumnIndexOrThrow("created")),
                s("text"), s("state"), s("remote"), s("manifest"), s("owner")))
        } }
    }
    fun update(id: String, vararg fields: Pair<String, String>) {
        writableDatabase.update("captures", ContentValues().apply { fields.forEach { put(it.first, it.second) } }, "id=?", arrayOf(id))
    }
    fun retry() { writableDatabase.execSQL("UPDATE captures SET state='pending' WHERE state IN ('auth_required','failed')") }
    fun manifest(c: Capture): ByteArray = JSONObject().apply {
        put("schemaVersion", 1); put("id", c.id); put("kind", c.kind)
        put("createdAt", java.time.Instant.ofEpochMilli(c.created).toString())
        put("mimeType", c.mime); put("text", c.text); put("driveFileId", c.remote)
        put("source", "Dashboard2.0")
    }.toString(2).toByteArray(Charsets.UTF_8)
}
