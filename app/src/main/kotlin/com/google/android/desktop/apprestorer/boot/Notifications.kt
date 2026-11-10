package com.google.android.desktop.apprestorer.boot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.google.android.desktop.apprestorer.MainActivity
import com.google.android.desktop.apprestorer.R
import com.google.android.desktop.apprestorer.model.WorkspaceSnapshot
import com.google.android.desktop.apprestorer.platform.IntentFactory
import com.google.android.desktop.apprestorer.ui.RestorePromptActivity

object Notifications {
  const val CHANNEL_RESTORE = "restore_prompt"
  const val CHANNEL_MONITOR = "workspace_monitor"
  const val MONITOR_NOTIFICATION_ID = 1001
  const val RESTORE_NOTIFICATION_ID = 1002

  fun ensureChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_RESTORE, context.getString(R.string.channel_restore), NotificationManager.IMPORTANCE_HIGH)
    )
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_MONITOR, context.getString(R.string.channel_monitor), NotificationManager.IMPORTANCE_MIN)
        .apply { setShowBadge(false) }
    )
  }

  fun monitorNotification(context: Context): Notification {
    val open = PendingIntent.getActivity(
      context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Builder(context, CHANNEL_MONITOR)
      .setSmallIcon(R.drawable.ic_stat_restorer)
      .setContentTitle(context.getString(R.string.monitor_title))
      .setContentText(context.getString(R.string.monitor_text))
      .setOngoing(true)
      .setContentIntent(open)
      .build()
  }

  /** Companion notification in case the floating prompt is dismissed or deferred. */
  fun postRestoreNotification(context: Context, pending: WorkspaceSnapshot) {
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    ensureChannels(context)
    val bounds = BootPromptLauncher.centeredPromptBounds(context)
    val pi = PendingIntent.getActivity(
      context, 1, RestorePromptActivity.intent(context, manualReview = true),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      IntentFactory.options(0, bounds, forPendingIntent = true).toBundle(),
    )
    val desks = pending.totalDeskCountByDisplay.values.sum().coerceAtLeast(1)
    val n = Notification.Builder(context, CHANNEL_RESTORE)
      .setSmallIcon(R.drawable.ic_stat_restorer)
      .setContentTitle(context.getString(R.string.restore_notification_title))
      .setContentText(context.getString(R.string.restore_notification_text, pending.windows.size, desks, pending.displays.size.coerceAtLeast(1)))
      .setContentIntent(pi)
      .setAutoCancel(true)
      .setCategory(Notification.CATEGORY_REMINDER)
      .build()
    context.getSystemService(NotificationManager::class.java)?.notify(RESTORE_NOTIFICATION_ID, n)
  }

  fun cancelRestoreNotification(context: Context) {
    context.getSystemService(NotificationManager::class.java)?.cancel(RESTORE_NOTIFICATION_ID)
  }
}
