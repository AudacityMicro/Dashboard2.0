package de.mm20.launcher2.health

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

data class FoodEntry(val id: String, val revision: Long, val name: String, val at: OffsetDateTime,
    val kcal: Double, val nutrients: Map<String, Double>, val estimated: Boolean = false) {
    fun json() = JSONObject().put("id", id).put("revision", revision).put("name", name).put("at", at.toString())
        .put("kcal", kcal).put("estimated", estimated).put("nutrients", JSONObject(nutrients.toSortedMap()))
}
data class HealthRead(val id: String, val type: String, val start: Instant, val end: Instant, val pageSize: Int, val pageToken: String?) {
    fun json() = JSONObject().put("id", id).put("type", type).put("start", start.toString()).put("end", end.toString())
        .put("pageSize", pageSize).put("pageToken", pageToken ?: JSONObject.NULL)
}
data class GoalCommand(val revision: Long, val goals: HealthGoals)
data class HealthCommands(val goal: GoalCommand?, val foods: List<FoodEntry>, val reads: List<HealthRead>) {
    companion object {
        fun digest(value: JSONObject): String = MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        fun parse(raw: String, now: Instant = Instant.now()): HealthCommands {
            require(raw.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "Health request file exceeds 512 KiB." }
            val root = JSONObject(raw); require(root.getDouble("schemaVersion") == 1.0)
            for (key in listOf("foods", "reads")) require(!root.has(key) || root.get(key) is JSONArray)
            require(!root.has("goal") || root.isNull("goal") || root.get("goal") is JSONObject)
            fun id(j: JSONObject) = j.getString("id").also { require(it.matches(Regex("[A-Za-z0-9._:-]{1,100}"))) }
            fun revision(j: JSONObject) = j.getLong("revision").also { require(it in 1..9_000_000_000_000_000L && j.getDouble("revision") == it.toDouble()) }
            fun number(j: JSONObject, key: String, maximum: Double = 100_000.0) = j.getDouble(key).also { require(it.isFinite() && it in 0.0..maximum) }
            val goal = root.optJSONObject("goal")?.let { j ->
                require(j.has("dailyFoodKcal") && j.has("creditActiveCalories"))
                val kcal = if (j.isNull("dailyFoodKcal")) null else number(j, "dailyFoodKcal").also { require(it > 0) }
                GoalCommand(revision(j), HealthGoals(kcal, j.getBoolean("creditActiveCalories")))
            }
            val foods = root.optJSONArray("foods") ?: JSONArray(); require(foods.length() <= 200)
            val entries = (0 until foods.length()).map { index -> foods.getJSONObject(index).let { j ->
                val at = OffsetDateTime.parse(j.getString("at"))
                require(at.toInstant() >= Instant.EPOCH && at.toInstant() <= now.plusSeconds(300)) { "Food needs a valid consumption time, not a future meal." }
                val name = j.getString("name").trim(); require(name.isNotEmpty() && name.length <= 200)
                val nutrients = listOf("proteinGrams", "carbsGrams", "fatGrams", "fiberGrams", "sugarGrams", "sodiumGrams")
                    .filter { j.has(it) && !j.isNull(it) }.associateWith { number(j, it, if (it == "sodiumGrams") 100.0 else 100_000.0) }
                FoodEntry(id(j), revision(j), name, at, number(j, "kcal"), nutrients, j.optBoolean("estimated"))
            } }
            require(entries.map { it.id }.distinct().size == entries.size)
            val reads = root.optJSONArray("reads") ?: JSONArray(); require(reads.length() <= 10)
            val queries = (0 until reads.length()).map { index -> reads.getJSONObject(index).let { j ->
                val start = OffsetDateTime.parse(j.getString("start")).toInstant()
                val end = OffsetDateTime.parse(j.getString("end")).toInstant()
                require(start >= Instant.EPOCH && end > start && end <= now.plusSeconds(300) && Duration.between(start, end) <= Duration.ofDays(31))
                val size = j.optInt("pageSize", 25); require(size in 1..100 && (!j.has("pageSize") || j.getDouble("pageSize") == size.toDouble()))
                val token = if (j.isNull("pageToken")) null else j.getString("pageToken").also { require(it.length <= 8192) }
                val type = j.getString("type"); require(type.matches(Regex("[A-Za-z0-9]{1,80}Record")))
                HealthRead(id(j), type, start, end, size, token)
            } }
            require(queries.map { it.id }.distinct().size == queries.size)
            return HealthCommands(goal, entries, queries)
        }
    }
}
