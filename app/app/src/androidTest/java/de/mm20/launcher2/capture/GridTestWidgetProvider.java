package de.mm20.launcher2.capture;

// Android may start this test-only provider outside instrumentation, without the target's Kotlin runtime.
public class GridTestWidgetProvider extends android.appwidget.AppWidgetProvider {
    @Override public void onUpdate(android.content.Context context, android.appwidget.AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            android.widget.RemoteViews views = new android.widget.RemoteViews("android", android.R.layout.simple_list_item_1);
            views.setTextViewText(android.R.id.text1, "Grid resize test");
            manager.updateAppWidget(id, views);
        }
    }
}
