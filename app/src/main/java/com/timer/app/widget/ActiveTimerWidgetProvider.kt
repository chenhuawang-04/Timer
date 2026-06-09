package com.timer.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.timer.app.timerApplication
import kotlinx.coroutines.launch

class ActiveTimerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        context.timerApplication().applicationScope.launch {
            TimerWidgetUpdater(context).refreshAll()
        }
    }
}
