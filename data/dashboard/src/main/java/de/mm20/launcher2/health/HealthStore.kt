package de.mm20.launcher2.health

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class HealthGoals(val foodKcal: Double? = null, val creditActive: Boolean = true)
data class HealthSnapshot(val date: String = "", val consumed: Double? = null, val active: Double? = null,
    val burned: Double? = null, val updated: Long = 0, val status: String = "Connect Health Connect in Settings.",
    val permissions: Set<String> = emptySet()) {
    fun remaining(goals: HealthGoals): Double? = if (date != LocalDate.now().toString() || consumed == null || goals.foodKcal == null) null
        else goals.foodKcal - consumed + if (goals.creditActive) active ?: 0.0 else 0.0
    fun json() = JSONObject().put("date", date).put("consumedKcal", consumed ?: JSONObject.NULL)
        .put("activeBurnedKcal", active ?: JSONObject.NULL).put("totalBurnedKcal", burned ?: JSONObject.NULL)
        .put("updatedAt", updated).put("status", status).put("permissions", JSONArray(permissions.toList()))
    companion object {
        fun parse(raw: String): HealthSnapshot = runCatching { JSONObject(raw).let { j ->
            fun number(key: String) = if (j.isNull(key)) null else j.getDouble(key).takeIf { it.isFinite() }
            HealthSnapshot(j.optString("date"), number("consumedKcal"), number("activeBurnedKcal"), number("totalBurnedKcal"),
                j.optLong("updatedAt"), j.optString("status"), j.optJSONArray("permissions")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }.toSet()
                }.orEmpty())
        } }.getOrDefault(HealthSnapshot())
    }
}

/** Health snapshots, user choices and command receipts never enter Android/cloud backup. */
class HealthStore(context: Context) : SQLiteOpenHelper(context,
    File(context.noBackupFilesDir, "health/health.db").also { it.parentFile!!.mkdirs() }.path, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE foods(id TEXT PRIMARY KEY,revision INTEGER NOT NULL,digest TEXT NOT NULL,record TEXT NOT NULL)")
        db.execSQL("CREATE TABLE reads(id TEXT PRIMARY KEY,digest TEXT NOT NULL,permission TEXT NOT NULL,data TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun meta(key: String) = readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else ""
    }
    fun set(key: String, value: String) { writableDatabase.insertWithOnConflict("meta", null,
        ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun flag(key: String) = meta(key) == "true"
    fun flag(key: String, value: Boolean) = set(key, value.toString())
    fun goals() = HealthGoals(meta("foodGoalKcal").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }, meta("creditActive") != "false")
    fun goals(value: HealthGoals) {
        require(value.foodKcal == null || value.foodKcal.isFinite() && value.foodKcal > 0 && value.foodKcal <= 100_000)
        writableDatabase.beginTransaction()
        try { set("foodGoalKcal", value.foodKcal?.toString().orEmpty()); flag("creditActive", value.creditActive); writableDatabase.setTransactionSuccessful() }
        finally { writableDatabase.endTransaction() }
    }
    fun snapshot() = HealthSnapshot.parse(meta("snapshot"))
    fun snapshot(value: HealthSnapshot) = set("snapshot", value.json().toString())
    fun food(id: String): JSONObject? = readableDatabase.rawQuery("SELECT revision,digest,record FROM foods WHERE id=?", arrayOf(id)).use {
        if (!it.moveToFirst()) null else JSONObject().put("revision", it.getLong(0)).put("digest", it.getString(1)).put("recordId", it.getString(2))
    }
    fun food(id: String, revision: Long, digest: String, record: String) { writableDatabase.insertWithOnConflict("foods", null,
        ContentValues().apply { put("id", id); put("revision", revision); put("digest", digest); put("record", record) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun read(id: String, digest: String, permissions: Set<String>): JSONObject? = readableDatabase.rawQuery(
        "SELECT digest,permission,data FROM reads WHERE id=?", arrayOf(id)).use {
        if (!it.moveToFirst()) null else {
            require(it.getString(0) == digest) { "Use a new request ID for a different health query." }
            if (it.getString(1) !in permissions) null else JSONObject(it.getString(2))
        }
    }
    fun read(id: String, digest: String, permission: String, data: JSONObject) { writableDatabase.insertWithOnConflict("reads", null,
        ContentValues().apply { put("id", id); put("digest", digest); put("permission", permission); put("data", data.toString()) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun clearReads() { writableDatabase.delete("reads", null, null) }
    fun retainReads(ids: Set<String>) {
        if (ids.isEmpty()) clearReads()
        else writableDatabase.delete("reads", "id NOT IN (${ids.joinToString(",") { "?" }})", ids.toTypedArray())
    }
    companion object {
        fun exists(context: Context) = File(context.noBackupFilesDir, "health/health.db").isFile
    }
}
