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
}
