package com.timer.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timer.app.timerApplication
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val app = context.timerApplication()
        app.applicationScope.launch {
            try {
                app.container.automationCoordinator.recoverAfterBoot()
            } finally {
                pending.finish()
            }
        }
    }
}
