package de.mm20.launcher2.dashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Layouts are private and independent from the agent-owned board and widget provider data. */
class WidgetGridStore(private val context: Context) {
    private fun key(parent: String, profile: GridProfile) = "grid.v1.$parent.${profile.name}"
    fun load(parent: String, profile: GridProfile): WidgetGridLayout = DashboardStore(context).use { store ->
        val raw = store.meta(key(parent, profile))
        if (raw.isEmpty()) return@use WidgetGridLayout(profile.columns)
        val json = JSONObject(raw)
        require(json.getInt("version") == 1)
        val columns = json.getInt("columns"); val row = json.getInt("rowDp")
        require(columns in 2..12 && row in 40..120)
        val items = json.getJSONArray("items"); require(items.length() <= 200)
        val cells = linkedMapOf<String, GridCell>(); val hidden = mutableSetOf<String>()
        repeat(items.length()) { i ->
            val item = items.getJSONObject(i); val id = item.getString("id")
            require(id.isNotEmpty() && id.length <= 120 && id !in cells)
            val cell = GridCell(item.getInt("x"), item.getInt("y"), item.getInt("w"), item.getInt("h"))
            require(cell.x >= 0 && cell.y >= 0 && cell.width in 1..columns && cell.x + cell.width <= columns &&
                cell.height in 1..32 && cell.y + cell.height <= 512)
            require(cells.values.none { cell.intersects(it) })
            cells[id] = cell; if (item.optBoolean("hidden")) hidden += id
        }
        WidgetGridLayout(columns, row, cells, hidden)
    }
    fun save(parent: String, profile: GridProfile, layout: WidgetGridLayout) {
        require(layout.cells.size <= 200)
        require(layout.columns in 2..12 && layout.rowDp in 40..120)
        val occupied = mutableListOf<GridCell>()
        layout.cells.forEach { (id, cell) ->
            require(id.isNotEmpty() && id.length <= 120 && cell.x >= 0 && cell.y >= 0 && cell.width in 1..layout.columns &&
                cell.x + cell.width <= layout.columns && cell.height in 1..32 && cell.y + cell.height <= 512 && occupied.none { cell.intersects(it) })
            occupied += cell
        }
        val json = JSONObject().put("version", 1).put("columns", layout.columns).put("rowDp", layout.rowDp)
            .put("items", JSONArray().apply {
                layout.cells.forEach { (id, c) -> put(JSONObject().put("id", id).put("x", c.x).put("y", c.y)
                    .put("w", c.width).put("h", c.height).put("hidden", id in layout.hidden)) }
            })
        DashboardStore(context).use { it.setMeta(key(parent, profile), json.toString()) }
    }
}
