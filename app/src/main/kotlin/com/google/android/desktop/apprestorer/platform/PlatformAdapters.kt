package com.google.android.desktop.apprestorer.platform

import android.app.ActivityOptions
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import com.google.android.desktop.apprestorer.capture.UsageEventRecord
import com.google.android.desktop.apprestorer.capture.UsageEventSource
import com.google.android.desktop.apprestorer.capture.UsageStatsWorkspaceReconciler
import com.google.android.desktop.apprestorer.model.DisplayGeometry
import com.google.android.desktop.apprestorer.model.WindowBounds
import com.google.android.desktop.apprestorer.restore.LaunchDescriptor
import com.google.android.desktop.apprestorer.restore.WindowLaunch

/** UsageStatsManager-backed event source (empty when Usage Access is not granted). */
class UsageStatsEventSource(context: Context) : UsageEventSource {
  private val usm = context.applicationContext.getSystemService(UsageStatsManager::class.java)

  override fun query(fromMs: Long, toMs: Long): List<UsageEventRecord> {
    val events = usm?.queryEvents(fromMs, toMs) ?: return emptyList()
    val out = ArrayList<UsageEventRecord>()
    val e = UsageEvents.Event()
    while (events.hasNextEvent()) {
      events.getNextEvent(e)
      when (e.eventType) {
        UsageStatsWorkspaceReconciler.ACTIVITY_RESUMED, UsageStatsWorkspaceReconciler.ACTIVITY_PAUSED,
        UsageStatsWorkspaceReconciler.ACTIVITY_STOPPED, UsageStatsWorkspaceReconciler.ACTIVITY_DESTROYED ->
          out += UsageEventRecord(e.packageName, e.className, e.eventType, e.timeStamp)
      }
    }
    return out
  }
}

/** Reads every display's size and usable work area (excluding taskbar / system bars). */
class DisplayGeometryProvider(private val context: Context, private val windowType: Int) {

  fun current(): List<DisplayGeometry> {
    val dm = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
    return dm.displays
      .filter { it.displayId == Display.DEFAULT_DISPLAY || it.state != Display.STATE_OFF }
      .mapNotNull { geometryFor(it) }
  }

  fun geometryFor(display: Display): DisplayGeometry? =
    try {
      val wc = context.createWindowContext(display, windowType, null)
      val metrics = wc.getSystemService(WindowManager::class.java).maximumWindowMetrics
      val b = metrics.bounds
      val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
        WindowInsets.Type.systemBars() or WindowInsets.Type.tappableElement()
      )
      DisplayGeometry(
        displayId = display.displayId,
        widthPx = b.width(),
        heightPx = b.height(),
        workArea = WindowBounds(b.left + insets.left, b.top + insets.top, b.right - insets.right, b.bottom - insets.bottom),
        densityDpi = wc.resources.configuration.densityDpi,
        name = display.name ?: "",
      )
    } catch (_: RuntimeException) {
      fallbackGeometry(display)
    }

  @Suppress("DEPRECATION")
  private fun fallbackGeometry(display: Display): DisplayGeometry {
    val mode = display.mode
    val w = mode.physicalWidth
    val h = mode.physicalHeight
    return DisplayGeometry(display.displayId, w, h, WindowBounds(0, 0, w, h), 160, display.name ?: "")
  }
}

/** Converts platform-free launch descriptors into Intents + ActivityOptions. */
object IntentFactory {

  fun toIntent(context: Context, d: LaunchDescriptor): Intent {
    val intent = Intent(d.action)
    if (d.component != null) {
      intent.component = ComponentName.unflattenFromString(d.component)
    } else {
      intent.setPackage(d.packageName)
      if (intent.resolveActivity(context.packageManager) == null) intent.setPackage(null)
    }
    d.categories.forEach { intent.addCategory(it) }
    d.dataUri?.let { intent.data = Uri.parse(it) }
    intent.addFlags(d.flags)
    d.booleanExtras.forEach { (k, v) -> intent.putExtra(k, v) }
    d.stringExtras.forEach { (k, v) -> intent.putExtra(k, v) }
    d.identifier?.let { intent.identifier = it }
    return intent
  }

  fun options(launch: WindowLaunch): ActivityOptions =
    options(launch.targetDisplayId, launch.targetBounds.takeIf { launch.applyBounds })

  @Suppress("DEPRECATION")
  fun options(displayId: Int, bounds: WindowBounds?, forPendingIntent: Boolean = false): ActivityOptions {
    val o = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
    bounds?.let { o.setLaunchBounds(Rect(it.left, it.top, it.right, it.bottom)) }
    if (forPendingIntent && Build.VERSION.SDK_INT >= 34) {
      o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    }
    return o
  }
}
