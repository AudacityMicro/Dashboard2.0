package de.mm20.launcher2.dashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Layouts are private and independent from the agent-owned board and widget provider data. */
class WidgetGridStore(private val context: Context) {
    private fun key(parent: String, profile: GridProfile) = "grid.v1.$parent.${profile.name}"
    fun load(parent: String, profile: GridProfile): WidgetGridLayout = DashboardStore(context).use { store ->
        val raw = store.meta(key(parent, profile))
        if (raw.isEmpty()) return@use configuration(profile)
        val json = JSONObject(raw)
        require(json.getInt("version") == 1)
        val columns = json.getInt("columns"); val row = json.getInt("rowDp")
        require(columns in 2..12 && row in 40..120)
        val items = json.getJSONArray("items"); require(items.length() <= 200)
        val cells = linkedMapOf<String, GridCell>(); val hidden = mutableSetOf<String>()
        val autoHeights = linkedMapOf<String, GridAutoHeight>()
        repeat(items.length()) { i ->
            val item = items.getJSONObject(i); val id = item.getString("id")
            require(id.isNotEmpty() && id.length <= 120 && id !in cells)
            val cell = GridCell(item.getInt("x"), item.getInt("y"), item.getInt("w"), item.getInt("h"))
            require(cell.x >= 0 && cell.y >= 0 && cell.width in 1..columns && cell.x + cell.width <= columns &&
                cell.height in 1..32 && cell.y + cell.height <= 512)
            require(cells.values.none { cell.intersects(it) })
            cells[id] = cell; if (item.optBoolean("hidden")) hidden += id
            if (item.has("autoHeight")) {
                val value = item.getJSONObject("autoHeight")
                autoHeights[id] = GridAutoHeight(value.getBoolean("enabled"), value.getInt("maxRows"))
            }
        }
        WidgetGridLayout(columns, row, cells, hidden, autoHeights)
    }
    fun save(parent: String, profile: GridProfile, layout: WidgetGridLayout) {
        DashboardStore(context).use { it.setMeta(key(parent, profile), encode(layout)) }
    }
    private fun encode(layout: WidgetGridLayout): String {
        require(layout.cells.size <= 200)
        require(layout.columns in 2..12 && layout.rowDp in 40..120)
        require(layout.autoHeights.keys.all { it in layout.cells })
        val occupied = mutableListOf<GridCell>()
        layout.cells.forEach { (id, cell) ->
            require(id.isNotEmpty() && id.length <= 120 && cell.x >= 0 && cell.y >= 0 && cell.width in 1..layout.columns &&
                cell.x + cell.width <= layout.columns && cell.height in 1..32 && cell.y + cell.height <= 512 && occupied.none { cell.intersects(it) })
            occupied += cell
        }
        val json = JSONObject().put("version", 1).put("columns", layout.columns).put("rowDp", layout.rowDp)
            .put("items", JSONArray().apply {
                layout.cells.forEach { (id, c) -> put(JSONObject().put("id", id).put("x", c.x).put("y", c.y)
                    .put("w", c.width).put("h", c.height).put("hidden", id in layout.hidden).apply {
                        layout.autoHeights[id]?.let { automatic -> put("autoHeight", JSONObject()
                            .put("enabled", automatic.enabled).put("maxRows", automatic.maxRows)) }
                    }) }
            })
        return json.toString()
    }
    fun configuration(profile: GridProfile): WidgetGridLayout = DashboardStore(context).use { store ->
        val raw = store.meta("grid.config.${profile.name}").ifEmpty { store.meta(key("home", profile)) }
        if (raw.isEmpty()) WidgetGridLayout(profile.columns) else JSONObject(raw).let {
            val columns = it.getInt("columns"); val row = it.getInt("rowDp")
            require(columns in 2..12 && row in 40..120)
            WidgetGridLayout(columns, row)
        }
    }
    /** Reflow every page atomically, so settings never leave profiles half-updated. */
    fun configure(profile: GridProfile, columns: Int, rowDp: Int) {
        val config = WidgetGridLayout(columns, rowDp)
        val encodedConfig = encode(config)
        DashboardStore(context).use { store ->
            val keys = store.readableDatabase.rawQuery("SELECT key FROM meta WHERE key LIKE ?", arrayOf("grid.v1.%.${profile.name}")).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            val updates = keys.associateWith { key ->
                val parent = key.removePrefix("grid.v1.").removeSuffix(".${profile.name}")
                encode(load(parent, profile).withColumns(columns).copy(rowDp = rowDp))
            }
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                updates.forEach { (key, value) -> store.setMeta(key, value) }
                store.setMeta("grid.config.${profile.name}", encodedConfig)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
}
