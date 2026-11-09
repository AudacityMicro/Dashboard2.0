package de.mm20.launcher2.capture

import de.mm20.launcher2.dashboard.*
import org.junit.Assert.*
import org.junit.Test

class WidgetGridLayoutTest {
    @Test fun defaultPackingFillsColumnsBeforeCreatingAnotherRow() {
        val grid = WidgetGridLayout(8).add("a", 3, 4).add("b", 2, 4).add("c", 2, 4)
        assertEquals(GridCell(0, 0, 4, 3), grid.cells["a"])
        assertEquals(GridCell(4, 0, 4, 2), grid.cells["b"])
        assertEquals(GridCell(4, 2, 4, 2), grid.cells["c"])
        noOverlap(grid)
    }
    private fun noOverlap(layout: WidgetGridLayout) {
        val cells = layout.cells.values.toList()
        for (i in cells.indices) for (j in i + 1 until cells.size) assertFalse(cells[i].intersects(cells[j]))
    }
    @Test fun itemsCanSitSideBySideAndSpanIndependentRowsAndColumns() {
        val grid = WidgetGridLayout(8).place("updates", GridCell(0, 0, 4, 3))
            .place("tasks", GridCell(4, 0, 4, 6)).place("weather", GridCell(0, 3, 2, 2))
        noOverlap(grid)
        assertEquals(0, grid.cells.getValue("tasks").y)
        assertEquals(GridCell(0, 3, 2, 2), grid.cells["weather"])
    }
    @Test fun resizingPushesIntersectingWidgetsWithoutLosingThem() {
        val grid = WidgetGridLayout(4).place("a", GridCell(0, 0, 2, 2)).place("b", GridCell(2, 0, 2, 2))
            .place("c", GridCell(0, 2, 4, 2)).place("a", GridCell(0, 0, 4, 4))
        noOverlap(grid); assertEquals(setOf("a", "b", "c"), grid.cells.keys)
        assertEquals(GridCell(0, 0, 4, 4), grid.cells["a"])
        assertTrue(grid.cells.getValue("b").y >= 4)
    }
    @Test fun movingAndChangingColumnsClampAllBounds() {
        var grid = WidgetGridLayout(8).place("a", GridCell(7, -3, 20, 100)).place("b", GridCell(0, 50, 4, 4))
        assertEquals(GridCell(0, 0, 8, 32), grid.cells["a"])
        grid = grid.withColumns(3)
        noOverlap(grid)
        grid.cells.values.forEach { assertTrue(it.x >= 0 && it.x + it.width <= 3) }
    }
    @Test fun profileDetectionUsesWindowOrientationAndFoldingInsteadOfLandscapeWidthAlone() {
        assertEquals(GridProfile.Portrait, GridProfile.detect(400, 900, false))
        assertEquals(GridProfile.Landscape, GridProfile.detect(900, 400, false))
        assertEquals(GridProfile.Unfolded, GridProfile.detect(700, 900, false))
        assertEquals(GridProfile.Unfolded, GridProfile.detect(580, 900, true))
    }
    @Test fun cornerResizingAnchorsOppositeCornerAndStaysInBounds() {
        val cell = GridCell(2, 3, 3, 4)
        assertEquals(GridCell(1, 2, 4, 5), cell.resized(-1, -1, true, true, 8))
        assertEquals(GridCell(2, 2, 4, 5), cell.resized(1, -1, false, true, 8))
        assertEquals(GridCell(1, 3, 4, 5), cell.resized(-1, 1, true, false, 8))
        assertEquals(GridCell(2, 3, 4, 5), cell.resized(1, 1, false, false, 8))
        val top = cell.resized(-99, -99, true, true, 8)
        assertEquals(5, top.x + top.width); assertEquals(7, top.y + top.height)
        assertEquals(0, top.x); assertEquals(0, top.y)
        assertEquals(GridCell(4, 6, 1, 1), cell.resized(99, 99, true, true, 8))
        assertEquals(GridCell(2, 3, 6, 32), cell.resized(99, 99, false, false, 8))
    }
    @Test fun contentHeightCapsGrowthAndReflowsOnlyAffectedColumns() {
        val base = WidgetGridLayout(4).place("updates", GridCell(0, 0, 2, 4))
            .place("side", GridCell(2, 0, 2, 3)).place("tasks", GridCell(0, 5, 2, 3))
            .copy(autoHeights = mapOf("updates" to GridAutoHeight(true, 6)))
        val grown = base.withContentHeights(mapOf("updates" to 100))
        assertEquals(6, grown.cells.getValue("updates").height)
        assertEquals(7, grown.cells.getValue("tasks").y) // Preserve the one-row manual gap.
        assertEquals(base.cells["side"], grown.cells["side"])
        val shrunk = base.withContentHeights(mapOf("updates" to 1))
        assertEquals(2, shrunk.cells.getValue("tasks").y)
        assertEquals(4, base.cells.getValue("updates").height) // Saved anchors never change.
        noOverlap(grown); noOverlap(shrunk)
    }
    @Test fun growthAndShrinkageCarryThroughNearestPredecessors() {
        val base = WidgetGridLayout(4).place("a", GridCell(0, 0, 4, 4))
            .place("b", GridCell(0, 4, 4, 4)).place("c", GridCell(0, 8, 4, 2))
            .copy(autoHeights = mapOf("a" to GridAutoHeight(true, 8), "b" to GridAutoHeight(true, 8)))
        val display = base.withContentHeights(mapOf("a" to 8, "b" to 2))
        assertEquals(8, display.cells.getValue("b").y)
        assertEquals(10, display.cells.getValue("c").y)
        assertEquals(base.cells, base.withContentHeights(mapOf("a" to 4, "b" to 4)).cells)
        noOverlap(display)
    }
    @Test fun fullCanvasFallsBackToSafeCappedViewportsWithoutLosingTiles() {
        val base = WidgetGridLayout(4).place("a", GridCell(0, 0, 4, 2))
            .place("b", GridCell(0, 500, 4, 12))
            .copy(autoHeights = mapOf("a" to GridAutoHeight(true, 32)))
        val display = base.withContentHeights(mapOf("a" to 32))
        assertEquals(base.cells.keys, display.cells.keys)
        assertTrue(display.cells.values.all { it.y + it.height <= 512 })
        assertEquals(500, display.cells.getValue("b").y)
        noOverlap(display)
    }
    @Test fun fixedModeIgnoresMeasurementsAndGridChangesKeepAutomaticPreferences() {
        val base = WidgetGridLayout(4).place("a", GridCell(0, 0, 4, 3))
            .copy(autoHeights = mapOf("a" to GridAutoHeight(false, 6)))
        assertEquals(base, base.withContentHeights(mapOf("a" to 20)))
        assertEquals(base.autoHeights, base.withColumns(2).autoHeights)
        for (invalid in listOf(0, 33)) try { GridAutoHeight(true, invalid); fail("Invalid maximum accepted") }
            catch (_: IllegalArgumentException) { }
    }
}
