package com.timer.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.timer.app.timerApplication
import kotlinx.coroutines.launch

class TodaySummaryWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        context.timerApplication().applicationScope.launch {
            TimerWidgetUpdater(context).refreshAll()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            context.timerApplication().applicationScope.launch {
                TimerWidgetUpdater(context).refreshAll()
            }
        }
    }
}
