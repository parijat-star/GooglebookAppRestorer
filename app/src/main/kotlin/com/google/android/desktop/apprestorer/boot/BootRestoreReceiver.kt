package com.google.android.desktop.apprestorer.boot

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import com.google.android.desktop.apprestorer.data.WorkspaceRepository
import com.google.android.desktop.apprestorer.model.DisplayGeometry
import com.google.android.desktop.apprestorer.model.WindowBounds
import com.google.android.desktop.apprestorer.platform.DisplayGeometryProvider
import com.google.android.desktop.apprestorer.platform.IntentFactory
import com.google.android.desktop.apprestorer.service.WorkspaceCaptureAccessibilityService
import com.google.android.desktop.apprestorer.ui.RestorePromptActivity

/**
 * Direct-boot aware: on LOCKED_BOOT_COMPLETED the previous boot's snapshot is promoted to the
 * pending-restore slot (device-protected storage); after unlock the restore countdown popup is shown.
 */
class BootRestoreReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val repo = WorkspaceRepository(context)
    when (intent.action) {
      Intent.ACTION_LOCKED_BOOT_COMPLETED -> repo.promoteIfNewBoot()
      Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT_POWERON, Intent.ACTION_MY_PACKAGE_REPLACED -> {
        repo.promoteIfNewBoot()
        BootPromptLauncher.scheduleUnlockChecks(context)
      }
    }
  }

  companion object {
    const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
  }
}

object BootPromptLauncher {
  private const val TAG = "BootPromptLauncher"
  private const val UNLOCK_SETTLE_MS = 550L
  private val main = Handler(Looper.getMainLooper())
  @Volatile private var launchPending = false

  fun isDesktopReadyForPrompt(context: Context): Boolean {
    val um = context.getSystemService(UserManager::class.java)
    if (um != null && !um.isUserUnlocked) return false
    val pm = context.getSystemService(PowerManager::class.java)
    if (pm != null && !pm.isInteractive) return false
    val km = context.getSystemService(KeyguardManager::class.java)
    if (km != null && km.isKeyguardLocked) return false
    return true
  }

  /** Probes immediately and after brief unlock-transition delays (idempotent). */
  fun scheduleUnlockChecks(context: Context) {
    val appCtx = context.applicationContext
    maybePrompt(appCtx)
    main.postDelayed({ maybePrompt(appCtx) }, 750L)
    main.postDelayed({ maybePrompt(appCtx) }, 1_800L)
  }

  /**
   * Shows the floating reboot countdown popup once per boot after the user unlocks the desktop.
   * Never consumes the per-boot prompt slot while the lockscreen is still up.
   */
  fun maybePrompt(context: Context, force: Boolean = false): Boolean {
    val appCtx = context.applicationContext
    val repo = WorkspaceRepository(appCtx)
    repo.promoteIfNewBoot()
    val pending = repo.loadPending() ?: return false
    val token = repo.currentBootToken()
    if (!force && repo.wasPromptedThisBoot(token)) return false
    if (!force && (BootCountdownOverlay.isShowing || launchPending)) return false
    if (!force && !isDesktopReadyForPrompt(appCtx)) return false

    if (force) {
      if (!BootCountdownOverlay.show(appCtx, pending, autoCountdown = repo.autoRestore)) {
        launchPrompt(appCtx, manualReview = false)
      }
      repo.markPrompted(token)
      return true
    }

    launchPending = true
    main.postDelayed({
      launchPending = false
      if (repo.wasPromptedThisBoot(token) || BootCountdownOverlay.isShowing) return@postDelayed
      if (!isDesktopReadyForPrompt(appCtx)) return@postDelayed
      val latest = repo.loadPending() ?: return@postDelayed
      val shown = BootCountdownOverlay.show(appCtx, latest, autoCountdown = repo.autoRestore) ||
        ((Settings.canDrawOverlays(appCtx) || WorkspaceCaptureAccessibilityService.instance != null) &&
          launchPrompt(appCtx, manualReview = false))
      if (shown) {
        repo.markPrompted(token)
      } else {
        Notifications.postRestoreNotification(appCtx, latest)
        repo.markPrompted(token)
      }
    }, UNLOCK_SETTLE_MS)
    return true
  }

  fun launchPrompt(context: Context, manualReview: Boolean = true): Boolean =
    try {
      val starter = WorkspaceCaptureAccessibilityService.instance ?: context
      starter.startActivity(
        RestorePromptActivity.intent(context, manualReview = manualReview),
        IntentFactory.options(0, centeredPromptBounds(context)).toBundle(),
      )
      true
    } catch (e: RuntimeException) {
      Log.w(TAG, "Unable to launch restore prompt", e)
      false
    }

  /** Compact centered popup bounds on the primary display — never full screen. */
  fun centeredPromptBounds(context: Context): WindowBounds? {
    val geometry = DisplayGeometryProvider(context.applicationContext, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
      .current().firstOrNull { it.displayId == DisplayGeometry.DEFAULT_DISPLAY_ID } ?: return null
    return centeredBounds(geometry)
  }

  fun centeredBounds(g: DisplayGeometry): WindowBounds {
    val work = g.workArea
    val density = g.densityDpi / 160f
    val w = minOf((720 * density).toInt(), (work.width * 0.86f).toInt())
    val h = minOf((640 * density).toInt(), (work.height * 0.9f).toInt())
    val left = work.left + (work.width - w) / 2
    val top = work.top + (work.height - h) / 2
    return WindowBounds(left, top, left + w, top + h)
  }
}
