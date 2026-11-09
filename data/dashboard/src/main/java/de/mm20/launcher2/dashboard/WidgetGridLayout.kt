package de.mm20.launcher2.dashboard

enum class GridProfile(val columns: Int) {
    Portrait(4), Landscape(8), Unfolded(8);
    companion object {
        fun detect(widthDp: Int, heightDp: Int, folding: Boolean): GridProfile = when {
            folding || minOf(widthDp, heightDp) >= 600 -> Unfolded
            widthDp > heightDp -> Landscape
            else -> Portrait
        }
    }
}

data class GridCell(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun intersects(other: GridCell) = x < other.x + other.width && x + width > other.x &&
        y < other.y + other.height && y + height > other.y
}
data class WidgetGridLayout(val columns: Int, val rowDp: Int = 56,
    val cells: Map<String, GridCell> = emptyMap(), val hidden: Set<String> = emptySet()) {
    /** A moved/resized item takes its requested space; intersecting items move down without overlap. */
    fun place(id: String, desired: GridCell): WidgetGridLayout {
        require(columns in 2..12 && rowDp in 40..120)
        val width = desired.width.coerceIn(1, columns)
        val height = desired.height.coerceIn(1, 32)
        val cell = GridCell(desired.x.coerceIn(0, columns - width), desired.y.coerceIn(0, 512 - height), width, height)
        val result = linkedMapOf(id to cell)
        for ((key, original) in cells.entries.sortedWith(compareBy({ it.value.y }, { it.value.x }, { it.key }))) {
            if (key == id) continue
            var next = original.copy(width = original.width.coerceIn(1, columns), height = original.height.coerceIn(1, 32))
            next = next.copy(x = next.x.coerceIn(0, columns - next.width), y = next.y.coerceAtLeast(0))
            while (true) {
                val collision = result.values.firstOrNull { next.intersects(it) } ?: break
                next = next.copy(y = collision.y + collision.height)
                require(next.y + next.height <= 512) { "Grid is full" }
            }
            result[key] = next
        }
        return copy(cells = result)
    }
    fun add(id: String, rows: Int, width: Int = columns): WidgetGridLayout {
        if (id in cells) return this
        val w = width.coerceIn(1, columns); val h = rows.coerceIn(1, 32)
        for (y in 0..512 - h) for (x in 0..columns - w) {
            val cell = GridCell(x, y, w, h)
            if (cells.values.none { cell.intersects(it) }) return place(id, cell)
        }
        error("Grid is full")
    }
    fun withColumns(count: Int): WidgetGridLayout {
        require(count in 2..12)
        var result = copy(columns = count, cells = emptyMap())
        for ((id, cell) in cells.entries.sortedBy { it.value.y }) result = result.place(id, cell)
        return result
    }
}
