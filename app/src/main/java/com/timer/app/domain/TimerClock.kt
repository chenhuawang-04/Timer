package com.timer.app.domain

import android.os.SystemClock

interface TimerClock {
    fun nowEpochMillis(): Long
    fun elapsedRealtimeMillis(): Long
}

class AndroidTimerClock : TimerClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}
