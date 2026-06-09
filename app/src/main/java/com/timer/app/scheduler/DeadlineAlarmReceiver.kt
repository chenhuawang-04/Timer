package com.timer.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timer.app.timerApplication
import kotlinx.coroutines.launch

class DeadlineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DeadlineAlarmScheduler.ACTION_RECONCILE_AND_REMIND) return
        val pending = goAsync()
        val app = context.timerApplication()
        app.applicationScope.launch {
            try {
                app.container.automationCoordinator.onAlarm(
                    instanceId = intent.getStringExtra(DeadlineAlarmScheduler.EXTRA_INSTANCE_ID),
                    kind = intent.getStringExtra(DeadlineAlarmScheduler.EXTRA_KIND)
                )
            } finally {
                pending.finish()
            }
        }
    }
}
