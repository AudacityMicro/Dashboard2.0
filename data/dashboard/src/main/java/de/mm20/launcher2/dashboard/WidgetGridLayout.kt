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
    fun resized(columnDelta: Int, rowDelta: Int, startEdge: Boolean, topEdge: Boolean, columns: Int): GridCell {
        val newX = if (startEdge) (x + columnDelta).coerceIn(0, x + width - 1) else x
        val newY = if (topEdge) (y + rowDelta).coerceIn(maxOf(0, y + height - 32), y + height - 1) else y
        return GridCell(newX, newY,
            if (startEdge) x + width - newX else (width + columnDelta).coerceIn(1, columns - x),
            if (topEdge) y + height - newY else (height + rowDelta).coerceIn(1, minOf(32, 512 - y)))
    }
    fun intersects(other: GridCell) = x < other.x + other.width && x + width > other.x &&
        y < other.y + other.height && y + height > other.y
}
data class GridAutoHeight(val enabled: Boolean = false, val maxRows: Int = 12) {
    init { require(maxRows in 1..32) }
}

data class WidgetGridLayout(val columns: Int, val rowDp: Int = 56,
    val cells: Map<String, GridCell> = emptyMap(), val hidden: Set<String> = emptySet(),
    val autoHeights: Map<String, GridAutoHeight> = emptyMap()) {
    /** Derive display positions afresh: content changes never overwrite the user's saved anchors. */
    fun withContentHeights(rows: Map<String, Int>): WidgetGridLayout {
        if (autoHeights.values.none { it.enabled }) return this
        val result = linkedMapOf<String, GridCell>()
        val ordered = cells.entries.sortedWith(compareBy({ it.value.y }, { it.value.x }, { it.key }))
        for ((id, original) in ordered) {
            val automatic = autoHeights[id]?.takeIf { it.enabled }
            val height = if (automatic == null) original.height else
                (rows[id] ?: original.height).coerceIn(1, minOf(automatic.maxRows, 512 - original.y))
            // Follow the nearest original predecessor in each occupied column. This carries
            // growth and shrinkage through stacks while retaining manual gaps and side-by-side tiles.
            val predecessors = (original.x until original.x + original.width).mapNotNull { column ->
                result.keys.filter { key -> cells.getValue(key).let {
                    column in it.x until it.x + it.width && it.y + it.height <= original.y
                } }.maxByOrNull { key -> cells.getValue(key).let { it.y + it.height } }
            }.distinct()
            val shift = predecessors.maxOfOrNull { key ->
                val before = cells.getValue(key); val after = result.getValue(key)
                after.y + after.height - before.y - before.height
            } ?: 0
            var next = original.copy(y = (original.y + shift).coerceAtLeast(0), height = height)
            while (true) {
                val collision = result.values.firstOrNull { next.intersects(it) } ?: break
                next = next.copy(y = collision.y + collision.height)
            }
            if (next.y + next.height > 512) {
                // A full canvas must remain usable. Keep saved positions and scroll overflowing
                // content inside capped widgets instead of overlapping or losing a tile.
                return copy(cells = cells.mapValues { (key, cell) ->
                    autoHeights[key]?.takeIf { it.enabled }?.let { cell.copy(height = minOf(cell.height, it.maxRows)) } ?: cell
                })
            }
            result[id] = next
        }
        return copy(cells = result)
    }
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
