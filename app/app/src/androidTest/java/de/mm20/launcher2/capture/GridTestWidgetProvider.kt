package de.mm20.launcher2.capture

class GridTestWidgetProvider : android.appwidget.AppWidgetProvider() {
    override fun onUpdate(context: android.content.Context, manager: android.appwidget.AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> manager.updateAppWidget(id,
            android.widget.RemoteViews("android", android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "Grid resize test")
            }) }
    }
}
