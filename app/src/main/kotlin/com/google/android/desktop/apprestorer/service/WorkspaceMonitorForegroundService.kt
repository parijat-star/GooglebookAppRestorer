package com.google.android.desktop.apprestorer.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.google.android.desktop.apprestorer.boot.Notifications

/**
 * stopWithTask="false" foreground service that keeps the process (and the bound accessibility
 * service) at foreground priority after the user closes the Restorer window.
 */
class WorkspaceMonitorForegroundService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Notifications.ensureChannels(this)
    try {
      val notification = Notifications.monitorNotification(this)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(Notifications.MONITOR_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      } else {
        startForeground(Notifications.MONITOR_NOTIFICATION_ID, notification)
      }
    } catch (e: RuntimeException) {
      Log.w(TAG, "startForeground refused; relying on accessibility binding", e)
      stopSelf()
      return START_NOT_STICKY
    }
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    // Intentionally keep running: tracking must survive closing the app window.
  }

  companion object {
    private const val TAG = "WorkspaceMonitorFgs"

    fun start(context: Context) {
      try {
        context.startForegroundService(Intent(context, WorkspaceMonitorForegroundService::class.java))
      } catch (e: RuntimeException) {
        Log.w(TAG, "Unable to start monitor service", e)
      }
    }
  }
}
