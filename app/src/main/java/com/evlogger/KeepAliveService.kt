package com.evlogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Держит процесс живым в фоне (экран выключен), пока идёт запись. */
class KeepAliveService : Service() {
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("log", "Запись лога", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "log")
            .setContentTitle("EV Logger")
            .setContentText("Идёт запись данных с автомобиля")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(1, n)
        if (wl == null) {
            wl = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "evlogger:log").also { it.acquire() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wl?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
